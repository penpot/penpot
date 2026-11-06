;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runner.feature-comparison
  "Plan and run benchmarks for combinations of optional Cargo renderer features.

  `prepare` reads Cargo metadata and collects the selected benchmark cases.
  Call `format-plan` to inspect that data, or await `execute` to build and run
  each configuration in sequence. Each configuration disables Cargo defaults.
  An edge is a comparison between two configurations that differ by one
  requested feature, from disabled to enabled.

  Execution saves progress, run records, logs and edge reports for inspection
  after completion, failure or cancellation. Builds use the checkout's renderer
  and benchmark sources. Keep other renderer builds and watchers stopped until
  execution finishes."
  (:require
   ["fs" :as fs]
   ["node:timers/promises" :as timers]
   ["path" :as path]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.features.plan :as feature-plan]
   [benches.render-wasm.node.catalog.loader :as catalog]
   [benches.render-wasm.node.files :as files]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.outcome :as outcome]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.runner.build :as build]
   [benches.render-wasm.node.runner.run :as run]
   [benches.render-wasm.node.settings :as settings]
   [benches.render-wasm.node.storage.artifacts :as artifacts]
   [benches.render-wasm.node.storage.runs :as runs]
   [benches.render-wasm.node.time :as time]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.result :as result]
   [clojure.set :as set]
   [clojure.string :as str]))

(def ^:private warnings
  ["Timing values do not determine success."
   "Intervals are exploratory; the comparison records no p-value verdicts."])

(def default-services
  {:prepare-build build/prepare
   :collect-catalog catalog/collect
   :create-run-home runs/create-home
   :execute-run run/execute})

(defn create-home
  "Creates a directory and an empty log for a new feature comparison.

  Args:
    root: The frontend directory.
    comparison-id: Contains 1 to 128 ASCII letters, digits, underscores or
      hyphens, starting with a letter or digit.
    output-dir: With nil `output-dir`, uses
      `target/performance/render-wasm/feature-comparisons/<comparison-id>` under
      root. An explicit path resolves from the Node process's working directory.
      The chosen directory must not exist and must not be inside the managed
      builds tree, including through symlink ancestors.

  Returns:
    A map containing `:comparison-id`, `:dir`, `:log-path` and `:record-path`.
    The log is `feature-comparison.log`; `:record-path` names the future
    `feature-comparison.transit` file, which this function does not write.

  Throws:
    Invalid identifiers, rejected paths and filesystem failures throw an error;
    path and filesystem errors include `:path` when available."
  [root comparison-id output-dir]
  (artifacts/check-home-id comparison-id "invalid feature comparison id")
  (let [default-dir (path/join (artifacts/managed-root root)
                               "feature-comparisons" comparison-id)
        dir         (artifacts/check-output-dir (or output-dir default-dir))]
    (when (artifacts/inside-builds? root dir)
      (artifacts/invalid-home "feature comparison cannot use a directory inside builds" {:path dir}))
    (assoc (artifacts/create-home dir "feature-comparison"
                                  "cannot create feature comparison home")
           :comparison-id comparison-id)))

(defn write-record
  "Validates and saves a feature-comparison record.

  Encodes `record` as Transit after schema validation, then atomically replaces
  that file so readers see either the previous complete record or the new one.

  Args:
    home: Supplies `:record-path` in an existing directory.
    record: The feature-comparison record.

  Returns:
    The record.

  Throws:
    Validation, write and rename failures throw without replacing the previous
    record."
  [home record]
  (files/atomic-write (:record-path home) (result/encode record))
  record)

(defn- permissions
  [options]
  {:allow-build-differences (boolean (:allow-build-differences options))
   :allow-scene-differences (boolean (:allow-scene-differences options))
   :allow-all-differences (boolean (:allow-all-differences options))})

(defn- storage-error
  [cause log-path]
  (errors/fatal-error "cannot persist feature comparison"
                      {:type ::storage-error :cause (errors/message-of cause) :log-path log-path}
                      cause))

(defn- change-config
  [record slug f]
  (update record :configurations
          #(mapv (fn [config]
                   (if (= slug (:slug config))
                     (f config)
                     config))
                 %)))

(defn- change-edge
  [record from to f]
  (update record :edges
          #(mapv (fn [edge]
                   (if (and (= from (:from edge)) (= to (:to edge)))
                     (f edge)
                     edge))
                 %)))

(defn- config-by-slug
  [record slug]
  (some #(when (= slug (:slug %)) %) (:configurations record)))

(defn- persist
  "Save updated progress before exposing it as the current and last saved record."
  [state update]
  (let [next  (assoc (update @(:record-ref state)) :updated-at (time/now))
        saved (try
                (write-record (:home state) next)
                (catch :default cause
                  (throw (storage-error cause (get-in state [:home :log-path])))))]
    (reset! (:record-ref state) saved)
    (reset! (:saved-ref state) saved)
    saved))

(defn- initial-record
  [prepared comparison-id started-at]
  (let [plan    (:plan prepared)
        options (:options prepared)]
    {:type :feature-comparison
     :comparison-id comparison-id
     :started-at started-at
     :updated-at started-at
     :status :running
     :options options
     :permissions (permissions options)
     :cases (:cases prepared)
     :features (:features plan)
     :configurations (mapv #(assoc % :status :planned) (:configurations plan))
     :edges (mapv #(assoc % :status :planned) (:edges plan))
     :warnings warnings}))

(defn- fail-config
  [state slug phase cause]
  (persist state
           (fn [record]
             (change-config record slug
                            #(-> % (assoc :status :failed
                                          :failure (errors/evidence phase cause))
                                 (dissoc :reason))))))

(defn- interrupt-config
  [state slug signal failure]
  (persist state
           (fn [record]
             (change-config record slug
                            #(cond-> (assoc % :status :failed :reason signal)
                               (some? failure) (assoc :failure failure))))))

(defn- mark-configs-unattempted
  [state configurations reason]
  (when-let [[configuration & pending] (seq configurations)]
    (let [slug (:slug configuration)]
      (when (= :planned (:status (config-by-slug @(:record-ref state) slug)))
        (persist state #(change-config % slug (fn [entry]
                                                (assoc entry :status :unattempted :reason reason)))))
      (mark-configs-unattempted state pending reason))))

(defn- mark-edge
  [state edge status data]
  (persist state
           (fn [record]
             (change-edge record (:from edge) (:to edge)
                          #(merge % (assoc data :status status))))))

(defn- mark-edges-unattempted
  ([state reason]
   (mark-edges-unattempted state (:edges @(:record-ref state)) reason))
  ([state edges reason]
   (when-let [[edge & pending] (seq edges)]
     (when (= :planned (:status edge))
       (mark-edge state edge :unattempted {:reason reason}))
     (mark-edges-unattempted state pending reason))))

(defn- finish
  [state status exit-code causes]
  (let [record (persist state #(assoc % :status status :ended-at (time/now)))]
    {:comparison record
     :home (:home state)
     :log-path (get-in state [:home :log-path])
     :errors (vec (remove nil? causes))
     :exit-code exit-code}))

(defn- finish-root
  [state error]
  (let [record    @(:record-ref state)
        completed (and (every? #(= :completed (:status %)) (:configurations record))
                       (every? #(= :completed (:status %)) (:edges record)))]
    (if completed
      (finish state :completed 0 [error])
      (finish state :failed 1 [error]))))

(defn- interrupt-comparison
  [state signal error]
  (let [reason (str "interrupted by " signal)]
    (mark-configs-unattempted state (:configurations @(:record-ref state)) reason)
    (mark-edges-unattempted state reason)
    (finish state :interrupted (outcome/interruption-code signal) [error])))

(defn- record-created-build
  [state slug build]
  (persist state
           #(change-config % slug
                           (fn [configuration]
                             (-> configuration
                                 (assoc :status :building
                                        :build (select-keys build [:id :manifest-path :log-path]))
                                 (dissoc :failure :reason))))))

(defn- stop-outcome
  [slug reason cause]
  {:stop? true
   :reason (str "not attempted after " reason " in configuration " slug)
   :error cause})

(defn- fail-configuration
  [state slug phase reason cause]
  (fail-config state slug phase cause)
  (stop-outcome slug reason cause))

(defn- interrupt-configuration
  [state slug signal phase cause]
  (interrupt-config state slug signal (when cause (errors/evidence phase cause)))
  (cond-> {:interrupted? true :signal signal}
    cause (assoc :error cause)))

(defn- fail-or-interrupt
  [state slug phase cause]
  (if-let [signal (process/signal-name (:signal state))]
    (interrupt-configuration state slug signal phase cause)
    (fail-configuration state slug phase (str (name phase) " failure") cause)))

(defn- begin-configuration
  [state configuration]
  (let [slug (:slug configuration)]
    (if-let [signal (process/signal-name (:signal state))]
      {:configuration configuration
       :outcome (interrupt-configuration state slug signal nil nil)}
      (do
        (persist state #(change-config % slug (fn [entry]
                                                (-> entry (assoc :status :building)
                                                    (dissoc :failure :reason)))))
        {:configuration configuration}))))

(defn- accept-build
  [state flow selected]
  (let [{:keys [slug selection]} (:configuration flow)
        effective                (get-in selected [:manifest :functional :features])
        saved-selection          (get-in selected [:manifest :provenance :feature-selection])
        valid-features?          (and (vector? effective) (every? string? effective))]
    (if (and (= selection saved-selection) valid-features?)
      (do
        (persist state #(change-config % slug (fn [entry]
                                                (assoc entry :status :ready :effective-features effective))))
        (log/info (str "Configuration " slug " built"))
        (assoc flow :build selected))
      (let [cause (ex-info "renderer build manifest disagrees with the planned selection"
                           {:type ::invalid-build :log-path (:log-path selected)
                            :selection selection :saved-selection saved-selection})]
        (assoc flow :outcome (fail-configuration state slug :build "build failure" cause))))))

(defn- ^:async build-configuration
  [state flow]
  (if (:outcome flow)
    flow
    (let [{:keys [slug selection]} (:configuration flow)
          built                    (attempt
                                    (await ((:prepare-build (:services state))
                                            {:root (:root state)
                                             :selection selection
                                             :build-timeout-ms (get-in state [:options :build-timeout-ms])
                                             :signal (:signal state)
                                             :on-created (partial record-created-build state slug)})))
          signal                   (process/signal-name (:signal state))]
      (cond
        (failure? built)
        (assoc flow :outcome (fail-or-interrupt state slug :build built))

        signal
        (assoc flow :outcome (interrupt-configuration state slug signal nil nil))

        :else (accept-build state flow built)))))

(defn- check-catalog
  [state flow]
  (if (:outcome flow)
    flow
    (let [slug      (get-in flow [:configuration :slug])
          selected  (:build flow)
          mismatch? (-> (attempt
                         (not= ((:collect-catalog (:services state))
                                selected
                                {:master-seed (get-in state [:options :seed])
                                 :filter (get-in state [:options :filter])})
                               (:cases state)))
                        (errors/with-data {:log-path (:log-path selected)}))
          signal    (process/signal-name (:signal state))
          step      (cond
                      (failure? mismatch?) (fail-or-interrupt state slug :catalog mismatch?)
                      signal (interrupt-configuration state slug signal nil nil)
                      mismatch?
                      (fail-configuration state slug :catalog "catalog mismatch"
                                          (ex-info "build catalog disagrees with the prepared cases"
                                                   {:type ::catalog-mismatch
                                                    :log-path (:log-path selected)
                                                    :configuration slug})))]
      (cond-> flow step (assoc :outcome step)))))

(defn- allocate-run
  [state flow]
  (if (:outcome flow)
    flow
    (let [slug (get-in flow [:configuration :slug])
          home (attempt
                ((:create-run-home (:services state))
                 {:root (:root state) :run-id (str (random-uuid))
                  :output-dir (path/join (get-in state [:home :dir]) "configs" slug)}))]
      (if (failure? home)
        (assoc flow :outcome (fail-configuration state slug :run-home "run-home failure" home))
        (assoc flow :run-home home)))))

(defn- ^:async execute-run
  [state flow]
  (attempt
   (await ((:execute-run (:services state))
           (:root state) (:run-home flow) (:build flow) (:cases state)
           (run/get-execution-options (:options state))
           (:signal state)))))

(defn- get-evidence-error
  [slug home response]
  (or (some #(when (errors/fatal? %) %) (:errors response))
      (attempt
       (when-not (= (get-in response [:run :run-id]) (:run-id (runs/get-record (:record-path home))))
         (throw (ex-info "saved run identity disagrees with execution result"
                         {:type ::invalid-run :configuration slug}))))))

(defn- get-run-evidence
  [slug home response]
  (let [run            (:run response)
        home           (or (:home response) home)
        evidence-error (get-evidence-error slug home response)
        error          (errors/with-data (or evidence-error (first (:errors response)))
                         {:log-path (:log-path home)})]
    {:run run
     :home home
     :evidence-error evidence-error
     :error error
     :gpu-failure (some #(when (= :gpu (:stage %)) %) (get-in run [:metadata :diagnostics :failures]))
     :run-ref {:run-id (:run-id run) :record-path (:record-path home)
               :log-path (:log-path home) :exit-code (:exit-code response)}}))

(defn- get-run-failure
  [{:keys [run evidence-error gpu-failure error]}]
  (cond
    evidence-error (errors/evidence :run-evidence evidence-error)
    gpu-failure gpu-failure
    (map? (get-in run [:termination :failure])) (get-in run [:termination :failure])
    error (errors/evidence :run error)))

(defn- classify-run
  [signal evidence]
  (let [termination (get-in evidence [:run :termination :reason])
        completed   (and (nil? (:evidence-error evidence)) (= :completed termination)
                         (zero? (get-in evidence [:run-ref :exit-code])))
        interrupted (or signal (when (= :interrupted termination)
                                 (or (get-in evidence [:run :termination :signal]) "SIGINT")))
        failure     (get-run-failure evidence)
        reason      (or interrupted (when (and (not completed) (nil? failure)) "run did not complete"))]
    (assoc evidence :completed completed :signal interrupted :failure failure :reason reason)))

(defn- record-run-result
  [state slug {:keys [completed run-ref reason failure]}]
  (persist state
           #(change-config % slug
                           (fn [entry]
                             (if completed
                               (-> entry (assoc :status :completed :run run-ref) (dissoc :failure :reason))
                               (cond-> (assoc entry :status :failed :run run-ref)
                                 (some? reason) (assoc :reason reason)
                                 (some? failure) (assoc :failure failure)))))))

(defn- zero-attempt-outcome
  [slug home]
  (stop-outcome slug "a run with no attempts"
                (ex-info "configuration run made no attempts"
                         {:type ::zero-attempts :log-path (:log-path home) :configuration slug})))

(defn- config-run-outcome
  [slug {:keys [run home completed signal reason error evidence-error gpu-failure]}]
  (cond
    completed
    (if (empty? (:attempts run))
      (zero-attempt-outcome slug home)
      (do (log/info (str "Configuration " slug " completed")) {:stop? false}))

    signal {:interrupted? true :signal reason :error error}
    evidence-error (stop-outcome slug "run evidence failure" error)
    gpu-failure (stop-outcome slug "GPU failure"
                              (ex-info (:message gpu-failure)
                                       {:type ::gpu-required :log-path (:log-path home) :configuration slug}))
    (empty? (:attempts run)) (zero-attempt-outcome slug home)
    :else (do (log/warn (str "Configuration " slug " failed")) {:stop? false :error error})))

(defn- finish-config-run
  [state flow response]
  (let [slug     (get-in flow [:configuration :slug])
        facts    (get-run-evidence slug (:run-home flow) response)
        evidence (classify-run (process/signal-name (:signal state)) facts)]
    (record-run-result state slug evidence)
    (config-run-outcome slug evidence)))

(defn- get-missing-run-cause
  "Returns why execution produced no run record, or nil when it produced one."
  [response home]
  (-> (cond
        (failure? response) response
        (nil? (:run response)) (or (first (:errors response))
                                   (ex-info "run executor returned no record" {:type ::missing-run})))
      (errors/with-data {:log-path (:log-path home)})))

(defn- ^:async run-configuration
  [state flow]
  (if (:outcome flow)
    flow
    (let [slug (get-in flow [:configuration :slug])
          home (:run-home flow)]
      (persist state #(change-config % slug (fn [entry]
                                              (assoc entry :status :running
                                                     :run {:run-id (:run-id home)
                                                           :record-path (:record-path home)
                                                           :log-path (:log-path home)}))))
      (log/info (str "Configuration " slug " running"))
      (let [response (await (execute-run state flow))
            cause    (get-missing-run-cause response home)]
        (assoc flow :outcome (if cause
                               (fail-configuration state slug :run "run failure" cause)
                               (finish-config-run state flow response)))))))

(defn- ^:async execute-config
  [state configuration]
  (let [started    (begin-configuration state configuration)
        built      (await (build-configuration state started))
        catalogued (check-catalog state built)
        allocated  (allocate-run state catalogued)
        executed   (await (run-configuration state allocated))]
    (:outcome executed)))

(defn- sorted-features
  [features]
  (when-not (and (vector? features) (every? string? features))
    (throw (ex-info "run metadata lacks an effective feature vector"
                    {:type ::missing-features})))
  (vec (sort (distinct features))))

(defn- edge-facts
  [baseline candidate edge]
  (let [baseline-features (sorted-features
                           (get-in baseline [:metadata :build :functional :features]))
        candidate-features (sorted-features
                            (get-in candidate [:metadata :build :functional :features]))
        baseline-set (set baseline-features)
        candidate-set (set candidate-features)]
    {:from (:from edge)
     :to (:to edge)
     :feature (:feature edge)
     :baseline-features baseline-features
     :candidate-features candidate-features
     :added (vec (sort (set/difference candidate-set baseline-set)))
     :removed (vec (sort (set/difference baseline-set candidate-set)))}))

(defn- save-comparison
  [path comparison log-path]
  (let [text (result/encode comparison)]
    (try
      (files/atomic-write path text)
      (catch :default cause
        (throw (storage-error cause log-path))))))

(defn- unacknowledged-count
  [comparison]
  (reduce + 0 (map (fn [pair] (count (remove :waived? (:mismatches pair))))
                   (:pairs comparison))))

(defn- get-comparison-options
  [record facts]
  (assoc (:permissions record)
         :expected-feature-change (select-keys facts [:feature :baseline-features
                                                      :candidate-features :added :removed])
         :edge facts
         :on-unallowed-difference :record))

(defn- analyze-edge
  "Returns the path of the saved comparison and its count of unacknowledged
  differences, or the failure, fatal failures included."
  [state edge]
  (attempt
   :catch-fatal
   (let [record           @(:record-ref state)
         baseline-config  (config-by-slug record (:from edge))
         candidate-config (config-by-slug record (:to edge))
         baseline         (runs/get-record (get-in baseline-config [:run :record-path]))
         candidate        (runs/get-record (get-in candidate-config [:run :record-path]))
         facts            (edge-facts baseline candidate edge)
         comparison       (compare/compare-runs baseline candidate (get-comparison-options record facts))
         path             (path/join (get-in state [:home :dir])
                                     "comparisons"
                                     (str (:from edge) "__" (:to edge) ".transit"))]
     (save-comparison path comparison (get-in state [:home :log-path]))
     {:path path
      :unacknowledged (unacknowledged-count comparison)})))

(defn- record-edge-outcome
  "Records the result of analyzing an edge.

  Returns:
    The first error seen so far.

  Throws:
    A fatal failure after the edge and the remaining edges are recorded."
  [state edge result error]
  (cond
    (and (failure? result) (errors/fatal? result))
    (do
      (mark-edge state edge :failed {:failure (errors/evidence :comparison-storage result)})
      (mark-edges-unattempted state "not attempted after comparison storage failure")
      (throw result))

    (failure? result)
    (do
      (mark-edge state edge :failed {:failure (errors/evidence :comparison result)})
      (or error result))

    (pos? (:unacknowledged result))
    (do
      (log/warn (str "Comparison " (:from edge) " -> " (:to edge)
                     " has " (:unacknowledged result) " unacknowledged differences"))
      (mark-edge state edge :failed {:path (:path result)
                                     :failure {:unacknowledged-differences (:unacknowledged result)}})
      error)

    :else
    (do
      (mark-edge state edge :completed {:path (:path result)})
      error)))

(defn- ^:async analyze-edges
  [state edges error]
  (await (timers/setImmediate))
  (if-let [signal (process/signal-name (:signal state))]
    (do
      (mark-edges-unattempted state (str "interrupted by " signal))
      {:interrupted signal :error error})
    (if-let [[edge & pending] (seq edges)]
      (analyze-edges state pending (record-edge-outcome state edge (analyze-edge state edge) error))
      {:done true :error error})))

(defn- create-comparison-directory
  [state]
  (try
    (fs/mkdirSync (path/join (get-in state [:home :dir]) "comparisons") #js {:recursive true})
    (catch :default cause
      (throw (storage-error cause (get-in state [:home :log-path]))))))

(defn- ^:async run-edges
  [state error]
  (if-let [signal (process/signal-name (:signal state))]
    (interrupt-comparison state signal error)
    (do
      (create-comparison-directory state)
      (let [analyzed (attempt :catch-fatal
                              (await (analyze-edges state (:edges @(:record-ref state)) error)))]
        (cond
          (failure? analyzed) (finish state :failed 1 [analyzed error])
          (:interrupted analyzed) (interrupt-comparison state (:interrupted analyzed) (:error analyzed))
          :else (finish-root state (:error analyzed)))))))

(defn- ^:async run-comparisons
  [state configurations]
  (if-let [signal (process/signal-name (:signal state))]
    (interrupt-comparison state signal nil)
    (if-let [[configuration & pending] (seq configurations)]
      (let [step (await (execute-config state configuration))]
        (cond
          (:interrupted? step)
          (interrupt-comparison state (:signal step) (:error step))

          (:stop? step)
          (do
            (mark-configs-unattempted state pending (:reason step))
            (run-edges state (:error step)))

          :else (run-comparisons state pending)))
      (run-edges state nil))))

(defn- create-comparison-state
  [root prepared signal services home comparison-id]
  {:root root
   :home home
   :options (:options prepared)
   :cases (:cases prepared)
   :signal signal
   :services services
   :record-ref (atom (initial-record prepared comparison-id (time/now)))
   :saved-ref (atom nil)})

(defn- initialize-comparison
  [state]
  (let [initial @(:record-ref state)
        home     (:home state)
        saved    (write-record home initial)]
    (reset! (:record-ref state) saved)
    (reset! (:saved-ref state) saved)))

(defn- ^:async execute-comparison
  [state]
  (let [home (:home state)]
    (binding [log/*log-path* (:log-path home)]
      (let [result (attempt :catch-fatal
                            (initialize-comparison state)
                            (await (run-comparisons state (:configurations @(:record-ref state)))))]
        (if (failure? result)
          (outcome/failed result {:comparison @(:saved-ref state)
                                  :home home
                                  :log-path (:log-path home)})
          result)))))

(defn ^:async execute
  "Build and benchmark every prepared feature configuration, then compare runs.

  The caller must handle `:dry-run` by calling `format-plan` instead; this function
  always executes.

  Create a fresh comparison directory and save its initial record before the
  first build. Build and run configurations serially, checking each compiled
  catalog against the prepared cases. Runs live in `configs/<slug>` under the
  comparison directory; edge reports live in `comparisons/<from>__<to>.transit`.
  Build artifacts remain in the managed builds directory.

  Timing differences alone do not cause failure. Unacknowledged differences
  in build, scene or other comparison metadata do cause an edge to fail.
  The prepared options `:allow-build-differences` and `:allow-scene-differences`
  acknowledge their respective categories; `:allow-all-differences` acknowledges
  every compatibility mismatch. Reports retain acknowledged differences.

  Build or catalog failures stop later configurations. A failed run may allow
  later configurations when it retains attempts and usable evidence; missing
  evidence, GPU failure or zero attempts stop them. Cancellation skips remaining
  work. Files already written remain for diagnosis.

  Args:
    root: The frontend directory.
    prepared: The result of `prepare`.
    signal: An optional AbortSignal that cancels execution.
    settings: May contain `:services` overrides with the same contracts as the
      defaults: `:prepare-build` uses `node.runner.build/prepare`, `:collect-catalog`
      uses `node.catalog.loader/collect`, `:create-run-home` uses `node.storage.runs/create-home`,
      and `:execute-run` uses `node.runner.run/execute`. Omitted services use
      `default-services`.

  Returns:
    Await the returned promise for a map with `:comparison`, the last
    successfully saved progress record; `:home`, the comparison ID and paths;
    `:log-path`; `:errors`, retained error objects; and `:exit-code`. The exit
    code is 0 only when all configurations and edges complete, 1 on failure,
    or 130/143 on interruption.

    If storage fails, the returned `:comparison` reflects the last successful
    atomic write, or nil if no record was saved; `:home` is nil if directory
    creation failed. Inspect the saved statuses and `:errors` together, since
    `:errors` is not an exhaustive list of failed configurations or edges."
  [root prepared signal settings]
  (let [services      (merge default-services (:services settings))
        comparison-id (str (random-uuid))
        home          (attempt (create-home root comparison-id (:output-dir prepared)))]
    (if (failure? home)
      (outcome/failed home {:comparison nil :home nil})
      (await
       (execute-comparison
        (create-comparison-state root prepared signal services home comparison-id))))))



(defn- get-root-features
  "Read feature definitions for cwd/Cargo.toml from Cargo metadata JSON."
  [cwd text]
  (let [metadata (js->clj (js/JSON.parse text))
        manifest (fs/realpathSync (path/join cwd "Cargo.toml"))
        package  (first (filter #(= manifest (fs/realpathSync
                                              (path/resolve cwd (get % "manifest_path"))))
                                (get metadata "packages")))]
    (when-not package
      (throw (ex-info "Cargo metadata does not contain the renderer manifest"
                      {:type ::missing-package :manifest-path manifest})))
    (get package "features")))

(defn ^:async prepare
  "Collect and validate a feature comparison plan without building or running it.

  Run `cargo metadata --offline --locked --no-deps` in `render-wasm` with the
  inherited environment. Cargo and its offline metadata must be available.
  `:build-timeout-ms` bounds that subprocess in milliseconds, not the entire
  planning operation.
  Require empty declared Cargo defaults and known requested feature names;
  Cargo remains responsible for resolving feature activation during builds.

  Planning creates no comparison output and starts no build, server or browser,
  including for a dry-run.

  Args:
    root: The frontend directory, with render-wasm as its sibling.
    opts: Resolved CLI options, including defaults. `:features` supplies the
      requested Cargo feature names, `:seed` supplies the master scene seed,
      and `:filter` selects case IDs by substring. `:max-features` limits the
      requested feature count. Execution requires `:confirm` for more than
      eight configurations or a blank filter; `:dry-run` bypasses that
      confirmation requirement only.

      An optional `:output-dir` resolves from the Node process's working
      directory and must not exist. Check it without creating or reserving it.
      `execute` checks it again and also rejects paths inside the managed
      builds tree.
    signal: An optional AbortSignal that cancels the process.

  Returns:
    Await the promise for a map with `:plan`, `:cases`, `:output-dir` and
    `:options`. The `:plan` map contains `:features` in sorted order,
    `:configurations` with a `:slug` and Cargo `:selection`, and `:edges` with
    `:from` and `:to` slugs and a `:feature`. Slugs are bit strings in sorted
    feature order: `0` means disabled, `1` enabled. The `:cases` vector holds
    case descriptions collected once. `:output-dir` is the absolute explicit
    path or nil. `:options` contains the supplied options with normalized
    features and output path. Pass this map to `format-plan` or `execute`.

  Throws:
    Invalid options or paths, Cargo failures or cancellation, unsupported
    manifest features, and case collection failures reject the promise.
    An empty case selection is an error."
  [root opts signal]
  (let [plan       (feature-plan/plan (:features opts) opts)
        output-dir (artifacts/check-output-dir (:output-dir opts))
        cwd        (path/resolve root ".." "render-wasm")
        metadata   (await (process/run
                           {:command "cargo"
                            :args ["metadata" "--offline" "--locked" "--no-deps" "--format-version" "1"]
                            :cwd cwd
                            :deadline (process/deadline (:build-timeout-ms opts settings/default-build-timeout-ms))
                            :signal signal}))]
    (feature-plan/check-manifest (:features plan) (get-root-features cwd (:stdout metadata)))
    {:plan plan
     :cases (cases/collect-cases {:master-seed (:seed opts) :filter (:filter opts)})
     :output-dir output-dir
     :options (assoc opts :features (:features plan) :output-dir output-dir)}))

(defn format-plan
  "Return a text summary of the map produced by `prepare`.

  This function neither collects cases again nor reads the filesystem,
  starts processes or executes benchmarks.

  Returns:
    Feature names, configurations, comparison edges, case count, sampling
    counts and configured timeouts."
  [{:keys [plan cases options]}]
  (str "Feature comparison plan\n"
       "Features: " (str/join ", " (:features plan)) "\n"
       "Configurations (Cargo defaults disabled):\n"
       (str/join "\n" (map #(str "  " (:slug %) ": "
                                 (str/join ", " (get-in % [:selection :features])))
                           (:configurations plan))) "\n"
       "Edges:\n"
       (str/join "\n" (map #(str "  " (:from %) " -> " (:to %) ": " (:feature %))
                           (:edges plan))) "\n"
       "Cases: " (count cases) "\n"
       "Attempts per case: " (:warmups options) " warmups, " (:repetitions options) " measured\n"
       "Timeouts (ms): case " (:timeout-ms options)
       ", build " (:build-timeout-ms options) ", startup " (:startup-timeout-ms options) "\n"))
