;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.commands
  "Renderer commands and collection of raw command observations."
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
            [:map [:now fn?] [:check fn?]]]]])

(def ^:private valid-context? (sm/validator schema:context))
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
  [rtx events active]
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
                        :record #(do (check) (vswap! events conj %))})
    (check)
    scoped))

(defn ^:async ^:no-doc execute
  "Executes a context function and returns its outcome and ordered raw events.
  A completed execution includes its updated context. A failed execution
  includes its cause and any events recorded before failure."
  [rtx run]
  (let [events (volatile! [])
        active (volatile! true)
        result (try
                 (attempt :catch-fatal
                          (let [scoped (scope-context (await rtx) events active)]
                            (check-output scoped (await (run scoped)))))
                 (finally
                   (vreset! active false)))]
    (if (failure? result)
      {:outcome :failed :error result :events @events}
      {:outcome :completed :context result :events @events})))

(defn- prepare-invocation
  [{:keys [command measured requires] :as invocation} rtx prepare args]
  (check-context rtx)
  (let [{:keys [check now] :as hooks} (:hooks rtx)
        owner                       (.get owners check)]
    (when-not (sm/validate (into [:map] (map #(vector % fn?) requires)) hooks)
      (throw (ex-info "command requires callable hooks"
                      {:type ::invalid-capabilities :command command
                       :required requires})))
    (check)
    (when (and measured (nil? owner))
      (throw (ex-info "measured command requires an execution"
                      {:type ::missing-execution :command command})))
    (assoc invocation
           :input rtx
           :core (apply prepare rtx args)
           :now now
           :record (:record owner))))

(defn- run-core
  [{:keys [command measured composite core now] :as invocation}]
  (if composite
    (assoc invocation :output (core))
    (let [start  (when measured (now))
          output (core)
          end    (when measured (now))]
      (when (and (some? output) (fn? (.-then output)))
        (.catch (js/Promise.resolve output) errors/as-failure)
        (throw (ex-info "synchronous command returned asynchronous work"
                        {:type ::async-result :command command})))
      (cond-> (assoc invocation :output output)
        measured (assoc :elapsed (- end start))))))

(defn- finish-invocation
  [{:keys [command measured input output elapsed record]}]
  (check-output input output)
  (when measured
    (record {:command command :metrics {:command/elapsed [elapsed]}}))
  output)

(defn ^:async invoke
  "Resolves a command input and executes its declared core.
  Measured invocations append one raw elapsed observation to their execution."
  [command rtx prepare args]
  (let [invocation (prepare-invocation command (await rtx) prepare args)
        result     (run-core invocation)]
    (finish-invocation
     (if (:composite command)
       (assoc result :output (await (:output result)))
       result))))
