;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.commands
  "Execution of renderer commands and collection of their measurements.

  A command transforms a command context: a map containing case data (`:case`),
  the prepared scene snapshot (`:scene`), the current camera view (`:view`) and
  functions for clocks, scheduling and renderer operations (`:hooks`). A scene
  is a recipe for shapes; a case pairs a scene with its work. A snapshot contains
  the concrete shapes and their references. A view contains zoom scale, camera
  offsets and optional viewport settings for canvas dimensions and pixel ratio.
  The `:check` hook is a guard:
  returning authorizes work, and throwing an error rejects it.

  A command execution evaluates a function from one context to the next and
  records measured command calls in completion order. Each call records an
  event with the command name and its metrics. A metric names a quantity;
  each event keeps its raw numeric
  values in a vector. An optional trace contains details such as render calls
  and waits. Each execution owns its event history and authorizes work while
  active. Its outcome contains the next context or an error and earlier events."
  (:require-macros [benches.render-wasm.runtime.commands])
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.declarations :as decl]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]))

(def ^:private schema:context
  [:map {:closed true}
   [:case [:map [:id qualified-keyword?]]]
   [:scene [:map [:objects map?] [:refs map?]]]
   [:view decl/schema:view]
   [:hooks [:and
            [:map-of :keyword fn?]
            [:map
             [:now fn?]
             [:check fn?]
             [:frame fn?]
             [:sleep fn?]
             [:render fn?]
             [:render-sync fn?]
             [:render-from-cache fn?]
             [:set-view fn?]
             [:set-view-start fn?]
             [:set-view-end fn?]]]]])

(def ^:private valid-context? (sm/validator schema:context))
(def ^:private schema:completion
  [:map {:closed true}
   [:end-ms {:optional true} number?]
   [:metrics {:optional true}
    [:map-of qualified-keyword? [:vector {:min 1} number?]]]
   [:trace {:optional true} :any]])
(def ^:private valid-completion? (sm/validator schema:completion))
(def ^:private owners (js/WeakMap.))

(defn- check-context
  [rtx]
  (when-not (valid-context? rtx)
    (throw (ex-info "invalid command context"
                    {:type ::invalid-context
                     ::sm/explain (sm/explain schema:context rtx)}))))

(defn- check-output
  [rtx output]
  (check-context output)
  (when-not (identical? (get-in rtx [:hooks :check])
                        (get-in output [:hooks :check]))
    (throw (ex-info "command changed execution ownership"
                    {:type ::ownership-lost})))
  ((get-in rtx [:hooks :check]))
  output)

(defn- scope-context
  [rtx events diagnostics active]
  (check-context rtx)
  (let [previous-check (get-in rtx [:hooks :check])
        previous-owner (.get owners previous-check)
        base-check     (or (:base-check previous-owner) previous-check)
        check          (fn []
                         (when-not @active
                           (throw (ex-info "command execution has ended"
                                           {:type ::inactive-execution})))
                         (base-check))
        scoped         (assoc-in rtx [:hooks :check] check)]
    (.set owners check {:base-check base-check
                        :record #(do (check) (vswap! events conj %))
                        :diagnose #(when @active (vswap! diagnostics conj %))})
    (check)
    scoped))

(defn ^:async ^:no-doc execute
  "Runs `run` with a command context and resolves to its outcome.

  `run` returns the next context or its Promise. A successful outcome contains
  `:outcome :completed`, `:context` and ordered `:events`. A failed outcome
  contains `:outcome :failed`, `:error` and events recorded before failure.
  Existing Errors retain their identity; other thrown values become Errors
  that retain the value in `:thrown`.

  The context's guard authorizes work during this execution. Passing a returned
  context to another execution establishes a new authorization. Repeated calls
  to asynchronous completion callbacks can supply `:diagnostics` while the
  execution is active. Returned contexts must retain this execution's `:check`
  guard. A changed guard rejects completion."
  [rtx run]
  (let [events      (volatile! [])
        diagnostics (volatile! [])
        active      (volatile! true)
        result      (try
                      (attempt :catch-fatal
                               (let [scoped (scope-context (await rtx) events diagnostics active)]
                                 (check-output scoped (await (run scoped)))))
                      (finally
                        (vreset! active false)))]
    (cond-> (if (failure? result)
              {:outcome :failed :error result :events @events}
              {:outcome :completed :context result :events @events})
      (seq @diagnostics) (assoc :diagnostics @diagnostics))))

(defn- prepare-invocation
  [{:keys [command measured] :as invocation} rtx prepare args]
  (check-context rtx)
  (let [{:keys [check now]} (:hooks rtx)
        owner              (.get owners check)]
    (check)
    (when (and measured (nil? owner))
      (throw (ex-info "measured command requires an execution"
                      {:type ::missing-execution :command command})))
    (assoc invocation
           :input rtx
           :core (apply prepare rtx args)
           :now now
           :record (:record owner)
           :diagnose (:diagnose owner))))

(defn- thenable?
  [output]
  (and (some? output) (fn? (.-then output))))

(defn- run-core
  [{:keys [command measured composite core now] :as invocation}]
  (if composite
    (assoc invocation :output (core))
    (let [start  (when measured (now))
          output (core)
          end    (when measured (now))]
      (when (thenable? output)
        (.catch (js/Promise.resolve output) errors/as-failure)
        (throw (ex-info "synchronous command returned asynchronous work"
                        {:type ::async-result :command command})))
      (cond-> (assoc invocation :output output)
        measured (assoc :metrics {:command/elapsed [(- end start)]})))))

(defn- finish-invocation
  [{:keys [measured input output record] :as invocation}]
  (check-output input output)
  (when measured
    (record (select-keys invocation [:command :metrics :trace])))
  output)

(defn- complete-invocation
  [{:keys [command measured now] :as invocation} output evidence start]
  ;; A completion callback can run after the renderer returns. A supplied
  ;; endpoint preserves the renderer return time across that scheduling delay.
  (let [end (when measured
              (if (and (map? evidence) (contains? evidence :end-ms))
                (:end-ms evidence)
                (now)))]
    (when-not (and (valid-completion? evidence)
                   (not (contains? (:metrics evidence) :command/elapsed)))
      (throw (ex-info "invalid command completion evidence"
                      {:type ::invalid-completion :command command
                       ::sm/explain (sm/explain schema:completion evidence)})))
    (cond-> (merge invocation evidence {:output output})
      measured (assoc-in [:metrics :command/elapsed] [(- end start)]))))

(defn- claim-terminal
  [terminal diagnose command signal]
  (if-some [first-signal @terminal]
    (do
      (when diagnose
        (diagnose {:type ::duplicate-terminal :command command
                   :first-signal first-signal :signal signal}))
      false)
    (do
      (vreset! terminal signal)
      true)))

(defn- run-async-core
  [{:keys [command measured core now diagnose] :as invocation}]
  (js/Promise.
   (fn [resolve reject]
     (let [terminal (volatile! nil)
           start    (volatile! nil)
           complete (fn complete
                      ([output] (complete output {}))
                      ([output evidence]
                       (when (claim-terminal terminal diagnose command :complete)
                         (let [result (attempt :catch-fatal
                                               (-> (complete-invocation invocation output evidence @start)
                                                   (finish-invocation)))]
                           (if (failure? result) (reject result) (resolve result))))
                       nil))
           fail     (fn [cause]
                      (when (claim-terminal terminal diagnose command :fail)
                        (reject (errors/as-failure cause)))
                      nil)
           result   (attempt :catch-fatal
                             (vreset! start (when measured (now)))
                             (let [returned (core complete fail)]
                               (when (thenable? returned)
                                 (.catch (js/Promise.resolve returned) fail))))]
       (when (failure? result)
         (fail result))))))

(defn ^:async invoke
  "Runs a command with a context or its Promise and returns the next context.
  Resolves to that context as a Promise; errors reject it. A measured command
  records one event with elapsed time, additional metric values and any trace."
  [command rtx prepare args]
  (let [invocation (prepare-invocation command (await rtx) prepare args)]
    (if (:async command)
      (await (run-async-core invocation))
      (let [result (run-core invocation)]
        (finish-invocation
         (if (:composite command)
           (assoc result :output (await (:output result)))
           result))))))
