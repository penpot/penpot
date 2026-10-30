;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.feature-comparison
  "Serial feature A/B orchestration and persistence of comparisons."
  (:require
   ["fs" :as fs]
   ["node:timers/promises" :as timers]
   ["path" :as path]
   [app.common.schema :as sm]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.catalog :as catalog]
   [benches.render-wasm.node.files :as files]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.run :as run]
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.result :as result]
   [clojure.set :as set]))

(def ^:private record-file "feature-comparison.transit")

(def ^:private warnings
  ["Timing values do not determine success."
   "Intervals are exploratory; the comparison records no p-value verdicts."])

(def default-services
  {:prepare-build build/prepare
   :collect-catalog catalog/collect
   :create-run-home runs/create-home
   :execute-run run/execute})

(defn- now
  []
  (.toISOString (js/Date.)))

(defn- invalid-home
  [message data]
  (throw (ex-info message (assoc data :type ::invalid-home))))

(defn- check-id
  [id]
  (when-not (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,127}" id))
    (invalid-home "invalid feature comparison id" {:id id}))
  id)

(defn create-home
  "Creates the feature-comparison home directory

  The default lives under the managed feature-comparisons directory.
  An explicit directory resolves from the caller's cwd and must not exist.
  Paths inside the managed builds tree are rejected."
  [root comparison-id output-dir]
  (check-id comparison-id)
  (let [default-dir (path/join (artifacts/managed-root root)
                               "feature-comparisons" comparison-id)
        dir         (runs/check-output-dir (or output-dir default-dir))]
    (when (artifacts/inside-builds? root dir)
      (invalid-home "feature comparison cannot use a directory inside builds" {:path dir}))
    (try
      (fs/mkdirSync dir #js {:recursive true})
      (fs/writeFileSync (path/join dir "feature-comparison.log") "" #js {:encoding "utf8" :flag "wx"})
      (catch :default cause
        (invalid-home "cannot create feature comparison home"
                      {:path dir :cause (or (ex-message cause) (str cause))})))
    {:dir dir
     :log-path (path/join dir "feature-comparison.log")
     :record-path (path/join dir record-file)}))

(defn write-record
  "Validates schema:feature-comparison and atomically replaces the saved file."
  [home record]
  (let [record (result/check-record record)]
    (files/atomic-write (:record-path home) (result/encode record))
    record))

(defn- permissions
  [options]
  {:allow-build-differences (boolean (:allow-build-differences options))
   :allow-scene-differences (boolean (:allow-scene-differences options))
   :allow-all-differences (boolean (:allow-all-differences options))})

(defn- data-of
  [cause]
  (try
    (ex-data cause)
    (catch :default _
      nil)))

(defn- signal-name
  [signal]
  (when (and signal (.-aborted ^js signal))
    (let [reason (.-reason ^js signal)]
      (cond
        (string? reason) reason
        (keyword? reason) (name reason)
        :else (or (:signal (data-of reason)) "SIGINT")))))

(defn- signal-exit
  [signal]
  (if (= "SIGTERM" signal) 143 130))

(defn- message-of
  [cause]
  (or (ex-message cause) (str cause)))

(defn- with-log-path
  [cause log-path]
  (ex-info (message-of cause)
           (assoc (data-of cause) :log-path (or (:log-path (data-of cause)) log-path))
           cause))

(defn- failure-evidence
  [phase cause]
  (cond-> {:phase (name phase)
           :message (message-of cause)
           :cause (failures/describe-cause cause)}
    (:log-path (data-of cause)) (assoc :tail-log (:log-path (data-of cause)))))

(defn- storage-error
  [cause log-path]
  (ex-info "cannot persist feature comparison"
           {:type ::storage-error :cause (message-of cause) :log-path log-path}
           cause))

(defn- storage-error?
  [cause]
  (loop [cause cause
         depth 0]
    (cond
      (nil? cause) false
      (contains? #{::storage-error ::log/storage-error} (:type (data-of cause))) true
      (>= depth 8) false
      :else (recur (ex-cause cause) (inc depth)))))

(defn- change-config
  [record slug f]
  (update record :configurations
          (fn [configurations]
            (mapv (fn [configuration]
                    (if (= slug (:slug configuration))
                      (f configuration)
                      configuration))
                  configurations))))

(defn- change-edge
  [record from to f]
  (update record :edges
          (fn [edges]
            (mapv (fn [edge]
                    (if (and (= from (:from edge)) (= to (:to edge)))
                      (f edge)
                      edge))
                  edges))))

(defn- config-by-slug
  [record slug]
  (some #(when (= slug (:slug %)) %) (:configurations record)))

(defn- persist
  [state update]
  (let [next  (assoc (update @(:record-ref state)) :updated-at (now))
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
                                          :failure (failure-evidence phase cause))
                                 (dissoc :reason))))))

(defn- interrupt-config
  [state slug signal failure]
  (persist state
           (fn [record]
             (change-config record slug
                            #(cond-> (assoc % :status :failed :reason signal)
                               (some? failure) (assoc :failure failure))))))

(defn- mark-configs-unattempted
  [state from-index reason]
  (loop [index from-index]
    (when (< index (count (:configurations @(:record-ref state))))
      (let [configuration (nth (:configurations @(:record-ref state)) index)]
        (when (= :planned (:status configuration))
          (persist state
                   (fn [record]
                     (change-config record (:slug configuration)
                                    #(assoc % :status :unattempted :reason reason)))))
        (recur (inc index))))))

(defn- mark-edge
  [state edge status data]
  (persist state
           (fn [record]
             (change-edge record (:from edge) (:to edge)
                          #(merge % (assoc data :status status))))))

(defn- mark-edges-unattempted
  [state reason]
  (doseq [edge (:edges @(:record-ref state))]
    (when (= :planned (:status edge))
      (mark-edge state edge :unattempted {:reason reason}))))

(defn- finish
  [state status exit-code error interrupted?]
  (let [record (persist state #(assoc % :status status :ended-at (now)))]
    {:comparison record
     :home (:home state)
     :log-path (get-in state [:home :log-path])
     :tail-log (:log-path (data-of error))
     :exit-code exit-code
     :error error
     :interrupted? interrupted?}))

(defn- finish-root
  [state error]
  (let [record    @(:record-ref state)
        completed (and (every? #(= :completed (:status %)) (:configurations record))
                       (every? #(= :completed (:status %)) (:edges record)))]
    (if completed
      (finish state :completed 0 error false)
      (finish state :failed 1 error false))))

(defn- interrupt-comparison
  [state signal error]
  (let [reason (str "interrupted by " signal)]
    (mark-configs-unattempted state 0 reason)
    (mark-edges-unattempted state reason)
    (finish state :interrupted (signal-exit signal) error true)))

(defn- ^:async prepare-build
  [state slug selection]
  (try
    (let [selected (await ((:prepare-build (:services state))
                           {:root (:root state)
                            :selection selection
                            :build-timeout-ms (get-in state [:options :build-timeout-ms])
                            :signal (:signal state)
                            :on-created
                            (fn [build]
                              (persist state
                                       (fn [record]
                                         (change-config
                                          record slug
                                          #(-> %
                                               (assoc :status :building
                                                      :build (select-keys build
                                                                          [:id :manifest-path :log-path]))
                                               (dissoc :failure :reason))))))}))]
      {:build selected})
    (catch :default cause
      (if (storage-error? cause)
        (throw cause)
        {:error cause}))))

(defn- load-run
  [path]
  (when-not (and (string? path) (fs/existsSync path))
    (throw (ex-info "run record is missing" {:type ::missing-run :path path})))
  (let [record (result/decode (fs/readFileSync path "utf8"))]
    (when-not (= :run-record (:type record))
      (throw (ex-info "expected a saved run record" {:type ::invalid-run :path path})))
    record))

(defn- ^:async execute-config
  [state configuration]
  (let [slug      (:slug configuration)
        selection (:selection configuration)
        signal    (:signal state)
        options   (:options state)
        cases     (:cases state)
        root      (:root state)
        home      (:home state)
        services  (:services state)]
    (if-let [interrupted (signal-name signal)]
      (do
        (interrupt-config state slug interrupted nil)
        {:interrupted? true :signal interrupted})
      (do
        (persist state
                 (fn [record]
                   (change-config record slug
                                  #(-> % (assoc :status :building)
                                       (dissoc :failure :reason)))))
        (let [prepared (await (prepare-build state slug selection))]
          (cond
            (:error prepared)
            (if-let [interrupted (signal-name signal)]
              (do
                (interrupt-config state slug interrupted
                                  (failure-evidence :build (:error prepared)))
                {:interrupted? true :signal interrupted :error (:error prepared)})
              (do
                (fail-config state slug :build (:error prepared))
                {:stop? true
                 :reason (str "not attempted after build failure in configuration " slug)
                 :error (:error prepared)}))

            (signal-name signal)
            (let [interrupted (signal-name signal)]
              (interrupt-config state slug interrupted nil)
              {:interrupted? true :signal interrupted})

            :else
            (let [selected         (:build prepared)
                  manifest         (:manifest selected)
                  effective        (get-in manifest [:functional :features])
                  saved-selection  (get-in manifest [:provenance :feature-selection])
                  valid-features?  (and (vector? effective) (every? string? effective))]
              (if-not (and (= selection saved-selection) valid-features?)
                (let [cause (ex-info "renderer build manifest disagrees with the planned selection"
                                     {:type ::invalid-build
                                      :log-path (:log-path selected)
                                      :selection selection
                                      :saved-selection saved-selection})]
                  (fail-config state slug :build cause)
                  {:stop? true
                   :reason (str "not attempted after build failure in configuration " slug)
                   :error cause})
                (do
                  (persist state
                           (fn [record]
                             (change-config record slug
                                            #(assoc % :status :ready
                                                    :effective-features effective))))
                  (log/info (str "Configuration " slug " built"))
                  (let [catalog-result
                        (try
                          (let [collected ((:collect-catalog services)
                                           selected
                                           {:master-seed (:seed options)
                                            :filter (:filter options)})]
                            (if (= collected cases)
                              {:cases collected}
                              {:mismatch true}))
                          (catch :default cause
                            (if (storage-error? cause)
                              (throw cause)
                              {:error (with-log-path cause (:log-path selected))})))]
                    (cond
                      (:error catalog-result)
                      (if-let [interrupted (signal-name signal)]
                        (do
                          (interrupt-config state slug interrupted
                                            (failure-evidence :catalog (:error catalog-result)))
                          {:interrupted? true :signal interrupted :error (:error catalog-result)})
                        (do
                          (fail-config state slug :catalog (:error catalog-result))
                          {:stop? true
                           :reason (str "not attempted after catalog failure in configuration " slug)
                           :error (:error catalog-result)}))

                      (signal-name signal)
                      (let [interrupted (signal-name signal)]
                        (interrupt-config state slug interrupted nil)
                        {:interrupted? true :signal interrupted})

                      (:mismatch catalog-result)
                      (let [cause (ex-info "build catalog disagrees with the prepared cases"
                                           {:type ::catalog-mismatch
                                            :log-path (:log-path selected)
                                            :configuration slug})]
                        (fail-config state slug :catalog cause)
                        {:stop? true
                         :reason (str "not attempted after catalog mismatch in configuration " slug)
                         :error cause})

                      :else
                      (let [run-id            (str (random-uuid))
                            run-home-result   (try
                                                {:home ((:create-run-home services)
                                                        {:root root
                                                         :run-id run-id
                                                         :output-dir (path/join (:dir home) "configs" slug)})}
                                                (catch :default cause
                                                  (if (storage-error? cause)
                                                    (throw cause)
                                                    {:error cause})))]
                        (if-let [cause (:error run-home-result)]
                          (do
                            (fail-config state slug :run-home cause)
                            {:stop? true
                             :reason (str "not attempted after run-home failure in configuration " slug)
                             :error cause})
                          (let [run-home (:home run-home-result)]
                            (persist state
                                     (fn [record]
                                       (change-config
                                        record slug
                                        #(assoc % :status :running
                                                :run {:run-id (:run-id run-home)
                                                      :record-path (:record-path run-home)
                                                      :log-path (:log-path run-home)}))))
                            (log/info (str "Configuration " slug " running"))
                            (let [run-result
                                  (try
                                    (await ((:execute-run services)
                                            root run-home selected cases
                                            (select-keys options (sm/keys run/schema:run-options))
                                            signal))
                                    (catch :default cause
                                      (if (storage-error? cause)
                                        (throw cause)
                                        {:error cause})))]
                              (if-let [run-record (:run run-result)]
                                (let [returned-home (:home run-result)
                                      run-home      (or returned-home run-home)
                                      exit-code     (:exit-code run-result)
                                      termination   (get-in run-record [:termination :reason])
                                      attempts      (:attempts run-record)
                                      gpu-failure   (some #(when (= :gpu (:stage %)) %)
                                                          (get-in run-record [:metadata :diagnostics :failures]))
                                      evidence-error
                                      (or (:storage-error run-result)
                                          (try
                                            (when-not (= (:run-id run-record)
                                                         (:run-id (load-run (:record-path run-home))))
                                              (throw (ex-info "saved run identity disagrees with execution result"
                                                              {:type ::invalid-run
                                                               :configuration slug})))
                                            nil
                                            (catch :default cause cause)))
                                      run-error     (when-let [cause (or evidence-error (:error run-result))]
                                                      (with-log-path cause (:log-path run-home)))
                                      interrupted   (or (signal-name signal)
                                                        (= :interrupted termination))
                                      completed     (and (nil? evidence-error)
                                                         (= :completed termination)
                                                         (zero? exit-code))
                                      run-failure   (cond
                                                      (some? evidence-error)
                                                      (failure-evidence :run-evidence evidence-error)

                                                      (some? gpu-failure)
                                                      gpu-failure

                                                      (map? (get-in run-record [:termination :failure]))
                                                      (get-in run-record [:termination :failure])

                                                      (some? run-error)
                                                      (failure-evidence :run run-error))
                                      reason        (cond
                                                      interrupted
                                                      (or (signal-name signal)
                                                          (get-in run-record [:termination :signal])
                                                          "SIGINT")

                                                      (not completed)
                                                      (when-not run-failure "run did not complete"))
                                      run-ref       {:run-id (:run-id run-record)
                                                     :record-path (:record-path run-home)
                                                     :log-path (:log-path run-home)
                                                     :exit-code exit-code}]
                                  (if completed
                                    (do
                                      (persist state
                                               (fn [record]
                                                 (change-config
                                                  record slug
                                                  #(-> % (assoc :status :completed :run run-ref)
                                                       (dissoc :failure :reason)))))
                                      (if (empty? attempts)
                                        (let [cause (ex-info "configuration run made no attempts"
                                                             {:type ::zero-attempts
                                                              :log-path (:log-path run-home)
                                                              :configuration slug})]
                                          {:stop? true
                                           :reason (str "not attempted after a run with no attempts in configuration "
                                                        slug)
                                           :error cause})
                                        (do
                                          (log/info (str "Configuration " slug " completed"))
                                          {:stop? false})))
                                    (do
                                      (persist state
                                               (fn [record]
                                                 (change-config
                                                  record slug
                                                  #(cond-> (assoc % :status :failed :run run-ref)
                                                     (some? reason) (assoc :reason reason)
                                                     (some? run-failure) (assoc :failure run-failure)))))
                                      (cond
                                        interrupted
                                        {:interrupted? true :signal reason :error run-error}

                                        (some? evidence-error)
                                        {:stop? true
                                         :reason (str "not attempted after run evidence failure in configuration " slug)
                                         :error run-error}

                                        (some? gpu-failure)
                                        {:stop? true
                                         :reason (str "not attempted after GPU failure in configuration " slug)
                                         :error (ex-info (:message gpu-failure)
                                                         {:type ::gpu-required
                                                          :log-path (:log-path run-home)
                                                          :configuration slug})}

                                        :else
                                        (if (empty? attempts)
                                          (let [cause (ex-info "configuration run made no attempts"
                                                               {:type ::zero-attempts
                                                                :log-path (:log-path run-home)
                                                                :configuration slug})]
                                            {:stop? true
                                             :reason (str "not attempted after a run with no attempts in configuration "
                                                          slug)
                                             :error cause})
                                          (do
                                            (log/warn (str "Configuration " slug " failed"))
                                            {:stop? false :error run-error}))))))
                                (let [cause (with-log-path
                                              (or (:error run-result)
                                                  (ex-info "run executor returned no record" {:type ::missing-run}))
                                              (:log-path run-home))]
                                  (fail-config state slug :run cause)
                                  {:stop? true
                                   :reason (str "not attempted after run failure in configuration " slug)
                                   :error cause})))))))))))))))))

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
  (let [record (result/check-record comparison)
        text   (result/encode record)]
    (try
      (files/atomic-write path text)
      (catch :default cause
        (throw (storage-error cause log-path))))))

(defn- unacknowledged-count
  [comparison]
  (reduce + 0 (map (fn [pair] (count (remove :waived? (:mismatches pair))))
                   (:pairs comparison))))

(defn- analyze-edge
  [state edge]
  (try
    (let [record           @(:record-ref state)
          baseline-config  (config-by-slug record (:from edge))
          candidate-config (config-by-slug record (:to edge))
          baseline         (load-run (get-in baseline-config [:run :record-path]))
          candidate        (load-run (get-in candidate-config [:run :record-path]))
          facts            (edge-facts baseline candidate edge)
          expected         (select-keys facts [:feature :baseline-features
                                               :candidate-features :added :removed])
          comparison       (compare/compare-runs
                            baseline candidate
                            {:allow-build-differences
                             (get-in record [:permissions :allow-build-differences])
                             :allow-scene-differences
                             (get-in record [:permissions :allow-scene-differences])
                             :allow-all-differences
                             (get-in record [:permissions :allow-all-differences])
                             :expected-feature-change expected
                             :edge facts
                             :on-unallowed-difference :record})
          path             (path/join (get-in state [:home :dir])
                                      "comparisons"
                                      (str (:from edge) "__" (:to edge) ".transit"))]
      (save-comparison path comparison (get-in state [:home :log-path]))
      {:path path
       :unacknowledged (unacknowledged-count comparison)})
    (catch :default cause
      (if (storage-error? cause)
        {:storage-error cause}
        {:error cause}))))

(defn- ^:async run-edges
  [state error]
  (if-let [interrupted (signal-name (:signal state))]
    (interrupt-comparison state interrupted error)
    (let [comparisons (path/join (get-in state [:home :dir]) "comparisons")]
      (try
        (fs/mkdirSync comparisons #js {:recursive true})
        (catch :default cause
          (throw (storage-error cause (get-in state [:home :log-path])))))
      (let [outcome
            (loop [edges (:edges @(:record-ref state))
                   error error]
              (await (timers/setImmediate))
              (if-let [interrupted (signal-name (:signal state))]
                (do
                  (mark-edges-unattempted state (str "interrupted by " interrupted))
                  {:interrupted interrupted :error error})
                (if-let [edge (first edges)]
                  (let [result (analyze-edge state edge)]
                    (cond
                      (:storage-error result)
                      (do
                        (mark-edge state edge :failed
                                   {:failure (failure-evidence :comparison-storage
                                                               (:storage-error result))})
                        (mark-edges-unattempted state
                                                "not attempted after comparison storage failure")
                        {:storage (:storage-error result)})

                      (:error result)
                      (do
                        (mark-edge state edge :failed
                                   {:failure (failure-evidence :comparison (:error result))})
                        (recur (rest edges) (or error (:error result))))

                      (pos? (:unacknowledged result))
                      (do
                        (log/warn (str "Comparison " (:from edge) " -> " (:to edge)
                                       " has " (:unacknowledged result) " unacknowledged differences"))
                        (mark-edge state edge :failed
                                   {:path (:path result)
                                    :failure {:unacknowledged-differences (:unacknowledged result)}})
                        (recur (rest edges) error))

                      :else
                      (do
                        (mark-edge state edge :completed {:path (:path result)})
                        (recur (rest edges) error))))
                  {:done true :error error})))]
        (cond
          (:interrupted outcome)
          (interrupt-comparison state (:interrupted outcome) (:error outcome))

          (:storage outcome)
          (finish state :failed 1 (:storage outcome) false)

          :else
          (finish-root state (:error outcome)))))))

(defn- ^:async run-comparisons
  [state]
  (loop [index 0]
    (if (>= index (count (:configurations @(:record-ref state))))
      (if-let [interrupted (signal-name (:signal state))]
        (interrupt-comparison state interrupted nil)
        (run-edges state nil))
      (if-let [interrupted (signal-name (:signal state))]
        (interrupt-comparison state interrupted nil)
        (let [configuration (nth (:configurations @(:record-ref state)) index)
              outcome       (await (execute-config state configuration))]
          (cond
            (:interrupted? outcome)
            (interrupt-comparison state (:signal outcome) (:error outcome))

            (:stop? outcome)
            (do
              (mark-configs-unattempted state (inc index) (:reason outcome))
              (run-edges state (:error outcome)))

            :else
            (recur (inc index))))))))

(defn ^:async execute
  "Executes a prepared feature comparison and owns builds, runs and reports.

  - prepared: node.feature-plan/prepare output.
  - settings: accepts an optional :services map with :prepare-build, :collect-catalog,
    :create-run-home and :execute-run overrides.

  Operational failures are returned as :error. The saved record reflects the
  last successful atomic write."
  [root prepared signal settings]
  (let [services      (merge default-services (:services settings))
        comparison-id (str (random-uuid))
        home          (try
                        (create-home root comparison-id (:output-dir prepared))
                        (catch :default cause
                          {:error cause}))]
    (if-let [cause (:error home)]
      {:comparison nil
       :home nil
       :exit-code 1
       :error cause
       :interrupted? false}
      (let [started    (now)
            initial    (initial-record prepared comparison-id started)
            record-ref (atom initial)
            saved-ref  (atom nil)
            state      {:root root
                        :home home
                        :options (:options prepared)
                        :cases (:cases prepared)
                        :signal signal
                        :services services
                        :record-ref record-ref
                        :saved-ref saved-ref}]
        (binding [log/*log-path* (:log-path home)]
          (let [outcome (try
                          (log/result (str "Comparison " comparison-id))
                          (log/result (str "Output " (:dir home)))
                          (let [saved (write-record home initial)]
                            (reset! record-ref saved)
                            (reset! saved-ref saved))
                          (await (run-comparisons state))
                          (catch :default cause
                            {:comparison @saved-ref
                             :home home
                             :log-path (:log-path home)
                             :tail-log (:log-path (data-of cause))
                             :exit-code 1
                             :error cause
                             :interrupted? false}))]
            (try
              (log/result (str "Status " (cond
                                           (:interrupted? outcome) "interrupted"
                                           (zero? (:exit-code outcome)) "completed"
                                           :else "failed")))
              outcome
              (catch :default cause
                (assoc outcome :error (or (:error outcome) cause)
                       :storage-error cause
                       :exit-code (if (:interrupted? outcome) (:exit-code outcome) 1))))))))))
