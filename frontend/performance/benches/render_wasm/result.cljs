;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.result
  "Schemas for experiments and reports."
  (:require
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.features.plan :as feature-plan]
   [benches.render-wasm.quantities :as quantities]
   [benches.render-wasm.report.analysis :as analysis]))

(def schema:event
  "One measured command invocation with its ordered raw values."
  [:map {:closed true}
   [:command qualified-keyword?]
   [:metrics [:map-of qualified-keyword? [:vector {:min 1} number?]]]
   [:trace {:optional true} :any]])

(def schema:phase
  "Raw command history for one phase execution, including partial failures."
  [:map {:closed true}
   [:phase [:enum :preparation :main :reset]]
   [:preparation-id some?]
   [:outcome [:enum :completed :failed]]
   [:events [:vector schema:event]]
   [:diagnostics {:optional true} [:vector :any]]])

(def schema:fingerprint
  "The content digest of the actual validated starting snapshot."
  [:map {:closed true}
   [:algorithm [:= :sha-256]]
   [:digest [:re #"^[0-9a-f]{64}$"]]])

(def schema:preparation
  "Setup diagnostics and callback measurements for one owned scene."
  [:map {:closed true}
   [:preparation-id some?]
   [:case-id some?]
   [:warmup? boolean?]
   [:fingerprint {:optional true} schema:fingerprint]
   [:effective-graphics {:optional true} map?]
   [:diagnostics map?]
   [:phases [:vector schema:phase]]
   [:failure {:optional true} errors/schema:diagnostic]])

(def schema:attempt
  "One chronological execution; failure consumes a slot and may retain evidence."
  [:map {:closed true}
   [:attempt-id [:int {:min 0}]]
   [:case-id some?]
   [:preparation-id [:maybe some?]]
   [:warmup? boolean?]
   [:outcome [:enum :completed :failed]]
   [:phases [:vector schema:phase]]
   [:failure {:optional true} errors/schema:diagnostic]
   [:partial {:optional true} map?]])

(def schema:metadata
  "Git, functional build settings, environment and diagnostic provenance."
  [:map {:closed true}
   [:git [:map [:sha [:maybe string?]] [:dirty [:maybe boolean?]]]]
   [:build [:map
            [:functional map?]
            [:provenance {:optional true} map?]
            [:environment {:optional true} [:map-of :string :string]]]]
   [:environment map?]
   [:scored? boolean?]
   [:diagnostics {:optional true}
    [:map [:failures {:optional true} [:vector errors/schema:diagnostic]]]]])

(def schema:termination
  "A run's current state or final reason, with optional diagnostic details."
  [:map
   [:reason keyword?]
   [:failure {:optional true} errors/schema:diagnostic]])

(def schema:case-outcome
  [:map {:closed true}
   [:outcome [:enum :completed :failed :timed-out :interrupted]]
   [:failure {:optional true} errors/schema:diagnostic]])

(def schema:run
  "schema:run stores saved case definitions, independent of any registry.
  Build :functional holds compatibility settings; :provenance holds identities."
  [:map {:closed true}
   [:type [:= :run-record]]
   [:run-id some?]
   [:started-at string?]
   [:ended-at [:maybe string?]]
   [:plan [:map {:closed true}
           [:cases [:vector [:map [:id some?]]]]
           [:warmups [:int {:min 0}]]
           [:repetitions [:int {:min 0}]]
           [:timeout-policy [:map {:closed true} [:case-ms [:int {:min 1}]]]]]]
   [:metadata schema:metadata]
   [:analysis analysis/schema:settings]
   [:quantities [:map-of qualified-keyword? :any]]
   [:preparations [:vector schema:preparation]]
   [:attempts [:vector schema:attempt]]
   [:case-outcomes [:map-of :any schema:case-outcome]]
   [:remaining [:map-of :any [:map {:closed true}
                              [:warmups [:int {:min 0}]]
                              [:repetitions [:int {:min 0}]]]]]
   [:termination schema:termination]])

(def schema:estimate
  "A finite computed estimate or an explicit suppression reason."
  [:or
   [:map {:closed true} [:value [:fn #(and (number? %) (js/Number.isFinite %))]]]
   [:map {:closed true} [:reason keyword?] [:minimum {:optional true} [:int {:min 1}]]]])

(def schema:interval
  "Finite ordered percentile bounds or an explicit suppression reason."
  [:or
   [:and
    [:map {:closed true}
     [:lower [:fn #(and (number? %) (js/Number.isFinite %))]]
     [:upper [:fn #(and (number? %) (js/Number.isFinite %))]]]
    [:fn #(<= (:lower %) (:upper %))]]
   [:map {:closed true} [:reason keyword?] [:minimum {:optional true} [:int {:min 1}]]]])

(def schema:statistics
  "Observed statistics and supported intervals for one metric."
  [:or
   [:map {:closed true}
    [:count [:int {:min 0}]]
    [:mean schema:estimate] [:median schema:estimate] [:min schema:estimate] [:max schema:estimate]
    [:sample-sd schema:estimate] [:mad schema:estimate] [:p95 schema:estimate] [:p99 schema:estimate]
    [:median-ci schema:interval]]
   [:map {:closed true} [:reason keyword?] [:count [:int {:min 0}]]]])

(def schema:metric-report
  "A sum within each phase execution and statistics across eligible executions."
  [:map {:closed true}
   [:definitions [:vector quantities/schema:description]]
   [:statistic [:= :sum]]
   [:counts [:map {:closed true}
             [:valid [:int {:min 0}]] [:invalid [:int {:min 0}]] [:absent [:int {:min 0}]]
             [:failed [:int {:min 0}]] [:unattempted [:int {:min 0}]] [:warmup [:int {:min 0}]]
             [:invocations [:int {:min 0}]] [:raw-values [:int {:min 0}]]]]
   [:diagnostics [:vector map?]]
   [:statistics schema:statistics]])

(def schema:comparison-side
  "One saved case's definition, preparations and shared metric reports."
  [:map {:closed true}
   [:case-id some?] [:definition [:map [:id some?]]]
   [:outcome [:maybe schema:case-outcome]]
   [:preparations [:vector schema:preparation]]
   [:phases [:map-of :keyword [:map {:closed true}
                               [:executions nat-int?] [:completed nat-int?]
                               [:failed nat-int?] [:warmup nat-int?]]]]
   [:remaining [:map {:closed true} [:warmups [:int {:min 0}]] [:repetitions [:int {:min 0}]]]]
   [:failed-attempts [:vector schema:attempt]]
   [:metrics [:map-of :any schema:metric-report]]])

(def schema:provenance
  "Saved run identity, timestamps, metadata and termination for comparison."
  [:map {:closed true}
   [:run-id some?] [:started-at string?] [:ended-at [:maybe string?]]
   [:metadata schema:metadata] [:termination schema:termination]])

(def schema:permissions
  [:map {:closed true}
   [:allow-build-differences boolean?]
   [:allow-scene-differences boolean?]
   [:allow-all-differences boolean?]])

(def schema:feature-comparison
  "Saved feature comparison progress. Case data and options remain ordinary
  extensible maps; configuration/edge states and retained references are typed."
  [:map {:closed true}
   [:type [:= :feature-comparison]]
   [:comparison-id string?]
   [:started-at string?]
   [:updated-at string?]
   [:ended-at {:optional true} string?]
   [:status [:enum :planned :running :completed :failed :interrupted]]
   [:options :map]
   [:permissions schema:permissions]
   [:cases [:vector [:map [:id some?]]]]
   [:features [:vector string?]]
   [:configurations
    [:vector
     [:map
      [:slug string?]
      [:selection [:map {:closed true}
                   [:default-features? boolean?]
                   [:features [:vector string?]]]]
      [:status [:enum :planned :building :ready :running :completed :failed :unattempted]]
      [:build {:optional true} [:map [:id string?] [:manifest-path string?] [:log-path string?]]]
      [:run {:optional true} [:map [:run-id string?] [:record-path string?] [:log-path string?]
                              [:exit-code {:optional true} :int]]]
      [:effective-features {:optional true} [:vector string?]]
      [:failure {:optional true} errors/schema:diagnostic]
      [:reason {:optional true} string?]]]]
   [:edges
    [:vector
     [:map
      [:from string?] [:to string?] [:feature string?]
      [:status [:enum :planned :completed :failed :unattempted]]
      [:path {:optional true} string?]
      [:failure {:optional true}
       [:or errors/schema:diagnostic
        [:map {:closed true} [:unacknowledged-differences [:int {:min 1}]]]]]
      [:reason {:optional true} string?]]]]
   [:warnings [:vector string?]]])

(def schema:comparison
  "Resolved analysis, selected permissions, every mismatch and per-side reports.
  Every acknowledged difference has a rule; unacknowledged differences have none.
  Optional edge context records requested toggles and Cargo's actual effective
  sets/deltas, including aliases."
  [:map {:closed true}
   [:type [:= :comparison]]
   [:analysis analysis/schema:settings]
   [:baseline schema:provenance]
   [:candidate schema:provenance]
   [:permissions schema:permissions]
   [:edge {:optional true}
    [:map {:closed true}
     [:from string?] [:to string?] [:feature string?]
     [:baseline-features [:vector string?]] [:candidate-features [:vector string?]]
     [:added [:vector string?]] [:removed [:vector string?]]]]
   [:source-coverage string?]
   [:interpretation string?]
   [:pairs [:vector
            [:map {:closed true}
             [:explicit? boolean?]
             [:mismatches [:vector
                           [:and
                            [:map {:closed true}
                             [:field :any] [:baseline :any] [:candidate :any] [:waived? boolean?]
                             [:rule {:optional true} [:enum :feature-toggle :build :scene :all]]]
                            [:fn #(= (:waived? %) (contains? % :rule))]]]]
             [:baseline schema:comparison-side] [:candidate schema:comparison-side]
             [:metrics [:map-of :any [:map {:closed true}
                                      [:absolute schema:estimate] [:percentage schema:estimate]
                                      [:absolute-ci schema:interval] [:percentage-ci schema:interval]]]]]]]])

(defn case-name
  "Returns the saved case identifier as shown in commands and reports."
  [id]
  (if (keyword? id) (subs (str id) 1) (str id)))

(defn- require-valid!
  "Rejects malformed external data with enough context for the caller."
  [valid? message data]
  (when-not valid?
    (throw (ex-info message (assoc data :type ::invalid-record)))))

(defn- incompatible!
  "Rejects runs whose recorded evidence cannot be merged."
  [message data]
  (throw (ex-info message (assoc data :type ::incompatible-runs))))

(defn- require-compatible!
  "Requires equal values at `path`, naming the disagreeing field."
  [base additional path label]
  (let [base-value       (get-in base path)
        additional-value (get-in additional path)]
    (when-not (= base-value additional-value)
      (incompatible! (str "cannot merge runs with different " label)
                     {:field label
                      :path path
                      :base base-value
                      :additional additional-value}))))

(defn- initial-remaining
  "Creates each saved case's fixed budget of unattempted slots."
  [{:keys [cases warmups repetitions]}]
  (into {} (map (fn [{:keys [id]}] [id {:warmups warmups :repetitions repetitions}])) cases))

(defn- consume-slot
  "Consumes a warmup or measured slot; completed and failed attempts count alike."
  [remaining {:keys [case-id warmup?]}]
  (let [kind (if warmup? :warmups :repetitions)
        path [case-id kind]
        left (get-in remaining path)]
    (require-valid! (and (some? left) (pos? left)) "no planned slot for attempt"
                    {:case-id case-id :slot kind})
    (require-valid! (or warmup? (zero? (get-in remaining [case-id :warmups])))
                    "measured attempt precedes planned warmups" {:case-id case-id})
    (update-in remaining path dec)))

(defn has-measurements?
  "Reports whether a case has measured commands in its saved phase histories.

  Args:
    run: constructed or decoded run record.
    case-id: exact saved case identifier.

  Returns:
    True when any preparation, main or reset block contains a command event."
  [run case-id]
  (boolean
   (some (comp seq :events)
         (mapcat :phases (filter #(= case-id (:case-id %))
                                 (concat (:preparations run) (:attempts run)))))))

(defn get-unmeasured-cases
  "Returns saved case ids whose phase histories contain no measured commands.

  Args:
    run: constructed or decoded run record.

  Returns:
    A vector of case identifiers in plan order."
  [run]
  (into []
        (keep (fn [{:keys [id]}]
                (when-not (has-measurements? run id)
                  id)))
        (get-in run [:plan :cases])))

(defn- check-completion
  "Requires all planned attempts to succeed before a run can be completed."
  [run termination]
  (when (= :completed (:reason termination))
    (require-valid! (every? #(every? zero? (vals %)) (vals (:remaining run)))
                    "completed run still has unattempted slots" {})
    (require-valid! (not-any? #(= :failed (:outcome %)) (:attempts run))
                    "completed run has failed attempts" {})
    (require-valid! (not-any? :failure (:preparations run))
                    "completed run has failed preparations" {})
    (require-valid! (every? #(= :completed (:outcome %)) (vals (:case-outcomes run)))
                    "completed run has failed cases" {})
    (require-valid! (= (set (map :id (get-in run [:plan :cases]))) (set (keys (:case-outcomes run))))
                    "completed run needs every case outcome" {})
    (require-valid! (empty? (get-unmeasured-cases run))
                    "completed case has no measurements" {:case-ids (get-unmeasured-cases run)})))

(defn- check-phases
  [record allowed]
  (let [phases (:phases record)]
    (require-valid! (= (mapv :phase phases)
                       (filterv (set (map :phase phases)) allowed))
                    "phase order or identity is invalid" {})
    (doseq [phase phases]
      (require-valid! (= (:preparation-id record) (:preparation-id phase))
                      "phase references a different preparation" {})
      (doseq [event (:events phase)]
        (require-valid! (= 1 (count (get-in event [:metrics :command/elapsed])))
                        "command needs one elapsed value" {:command (:command event)})))
    (when-not (or (:failure record) (= :failed (:outcome record)))
      (require-valid! (every? #(= :completed (:outcome %)) phases)
                      "successful execution has a failed phase" {}))))

(defn- check-preparation
  [preparation]
  (require-valid! (sm/validate schema:preparation preparation) "invalid preparation" {})
  (check-phases preparation [:preparation])
  (when-not (:failure preparation)
    (require-valid! (and (:fingerprint preparation) (:effective-graphics preparation)
                         (= [:preparation] (mapv :phase (:phases preparation))))
                    "completed preparation needs scene, graphics and phase evidence" {})))

(defn- check-attempt
  [attempt preparation]
  (require-valid! (sm/validate schema:attempt attempt) "invalid attempt" {})
  (check-phases attempt [:main :reset])
  (when (:preparation-id attempt)
    (require-valid! (= (:case-id attempt) (:case-id preparation))
                    "attempt references an unknown or different preparation" {}))
  (when (= :completed (:outcome attempt))
    (require-valid! (and preparation (not (:failure preparation))
                         (not (:failure attempt))
                         (= :main (get-in attempt [:phases 0 :phase])))
                    "completed attempt needs successful preparation and main" {}))
  (when (seq (:phases attempt))
    (require-valid! (= :main (get-in attempt [:phases 0 :phase]))
                    "attempt phases must start with main" {})
    (require-valid! (not (:failure preparation)) "attempt phases need successful preparation" {}))
  (when (= :failed (get-in attempt [:phases 0 :outcome]))
    (require-valid! (= 1 (count (:phases attempt))) "reset follows failed main" {})))

(defn- check-case-outcome
  [run case-id outcome]
  (require-valid! (contains? (:remaining run) case-id) "unknown case outcome" {})
  (require-valid! (sm/validate schema:case-outcome outcome) "invalid case outcome" {})
  (when (= :completed (:outcome outcome))
    (require-valid! (and (not (:failure outcome))
                         (every? zero? (vals (get-in run [:remaining case-id])))
                         (not-any? #(and (= case-id (:case-id %)) (= :failed (:outcome %))) (:attempts run))
                         (not-any? #(and (= case-id (:case-id %)) (:failure %)) (:preparations run))
                         (has-measurements? run case-id))
                    "completed case has pending, failed or unmeasured work" {:case-id case-id})))

(defn check-run
  "Validates schema:run and chronological accounting at a saved-data boundary."
  [run]
  (require-valid! (sm/validate schema:run run) "invalid schema:run" {})
  (let [case-ids (mapv :id (get-in run [:plan :cases]))
        preps    (:preparations run)
        prep-ids (mapv :preparation-id preps)
        prep-map (into {} (map (juxt :preparation-id identity)) preps)
        left     (reduce consume-slot (initial-remaining (:plan run)) (:attempts run))]
    (require-valid! (= (count case-ids) (count (set case-ids))) "duplicate saved case" {})
    (require-valid! (= (count prep-ids) (count (set prep-ids))) "duplicate preparation" {})
    (require-valid! (every? #(contains? (set case-ids) %) (map :case-id preps)) "unknown preparation case" {})
    (require-valid! (= left (:remaining run)) "remaining slots disagree with attempts" {})
    (doseq [[case-id outcome] (:case-outcomes run)]
      (check-case-outcome run case-id outcome))
    (doseq [prep preps]
      (check-preparation prep))
    (doseq [[attempt-id attempt] (map-indexed vector (:attempts run))]
      (require-valid! (= attempt-id (:attempt-id attempt)) "attempts are not chronological" {})
      (check-attempt attempt (get prep-map (:preparation-id attempt))))
    (check-completion run (:termination run)))
  run)

(defn create-run
  "Creates an empty raw run from externally supplied identities and facts.
  The plan defaults to 3 warmups, 10 repetitions and a 30000 ms case timeout.
  For each case, all warmup slots precede measured slots. Failed attempts consume
  their slot too; attempts retain chronological order across cases."
  [{:keys [run-id started-at plan metadata analysis quantities]}]
  (let [plan (merge {:warmups 3 :repetitions 10 :timeout-policy {:case-ms 30000}} plan)]
    (check-run {:type :run-record
                :run-id run-id :started-at started-at :ended-at nil
                :plan plan :metadata metadata :analysis (analysis/resolve-settings analysis)
                :quantities (merge quantities/descriptions quantities)
                :preparations [] :attempts [] :case-outcomes {} :remaining (initial-remaining plan)
                :termination {:reason :running}})))

(defn- check-open!
  "Checks that a constructed or decoded run still accepts evidence."
  [run]
  (require-valid! (= :running (get-in run [:termination :reason])) "run already terminated" {}))

(defn record-preparation
  "Checks and appends new setup evidence without consuming an attempt slot.
  The run must come from create-run or decode; earlier evidence is trusted."
  [run preparation]
  (check-open! run)
  (require-valid! (not (contains? (:case-outcomes run) (:case-id preparation))) "case already terminated" {})
  (check-preparation preparation)
  (require-valid! (contains? (:remaining run) (:case-id preparation)) "unknown preparation case" {})
  (require-valid! (not-any? #(= (:preparation-id preparation) (:preparation-id %))
                            (:preparations run))
                  "duplicate preparation" {})
  (update run :preparations conj preparation))

(defn record-attempt
  "Checks and appends one new execution, consuming its scheduled slot.
  The run must come from create-run or decode.
  Missing preparation is allowed for failures before scene setup completes.
  Earlier attempts are trusted; encode and decode check the complete record."
  [run attempt]
  (check-open! run)
  (require-valid! (not (contains? (:case-outcomes run) (:case-id attempt))) "case already terminated" {})
  (let [attempt (-> (merge {:preparation-id nil :phases []} attempt)
                    (assoc :attempt-id (count (:attempts run))))]
    (check-attempt attempt (some #(when (= (:preparation-id attempt) (:preparation-id %)) %)
                                 (:preparations run)))
    (-> run
        (update :attempts conj attempt)
        (update :remaining consume-slot attempt))))

(defn finish-run
  "Records externally supplied end time and termination details, retaining gaps."
  [run ended-at termination]
  (check-open! run)
  (require-valid! (sm/validate schema:termination termination) "invalid termination" {})
  (require-valid! (not= :running (:reason termination)) "termination needs an end reason" {})
  (check-completion run termination)
  (assoc run :ended-at ended-at :termination termination))

(defn record-case-outcome
  "Records the terminal outcome of one case.

  Args:
    run: constructed or decoded run that accepts new evidence.
    case-id: exact saved case identifier.
    outcome: completion, failure, timeout or interruption with available cause.

  Returns:
    The run with its case outcome.

  Throws:
    ExceptionInfo for unknown cases, duplicate outcomes or malformed evidence."
  [run case-id outcome]
  (check-open! run)
  (require-valid! (not (contains? (:case-outcomes run) case-id)) "duplicate case outcome" {})
  (check-case-outcome run case-id outcome)
  (assoc-in run [:case-outcomes case-id] outcome))

(defn merge-runs
  "Returns a new validated run that appends `additional`'s measured attempts to
  `base`.

  Both runs must be completed and compatible. The merged plan keeps `base`'s
  warmups and adds `additional`'s measured repetitions. Warmup attempts
  and preparations used only by them are excluded from `additional`.

  The merged record uses the base run's quantity descriptions.
  The caller supplies the merged run id and may supply its end time."
  [base additional {:keys [run-id ended-at]}]
  (let [base       (check-run base)
        additional (check-run additional)]
    (require-valid! (and (string? run-id) (seq run-id))
                    "merged run needs a run id" {})
    (doseq [[side run] [[:base base] [:additional additional]]]
      (when-not (= :completed (get-in run [:termination :reason]))
        (incompatible! "only completed runs can be merged"
                       {:side side
                        :run-id (:run-id run)
                        :reason (get-in run [:termination :reason])})))
    (when-not (and (string? (:ended-at base))
                   (string? (:ended-at additional)))
      (incompatible! "completed runs need end times"
                     {:base (:ended-at base)
                      :additional (:ended-at additional)}))
    (doseq [[path label] [[[:plan :cases] "case definitions"]
                          [[:plan :timeout-policy] "timeout policy"]
                          [[:plan :warmups] "warmup count"]
                          [[:analysis] "analysis settings"]
                          [[:metadata :git] "git provenance"]
                          [[:metadata :build] "build facts"]
                          [[:metadata :environment] "runtime environment"]
                          [[:metadata :scored?] "scored flag"]]]
      (require-compatible! base additional path label))
    (doseq [run [base additional]]
      (require-valid! (every? #(sm/validate quantities/schema:description %)
                              (vals (:quantities run)))
                      "cannot merge malformed quantity descriptions" {:run-id (:run-id run)}))
    (when-not (= (update-vals (:quantities base) #(dissoc % :description))
                 (update-vals (:quantities additional) #(dissoc % :description)))
      (incompatible! "cannot merge runs with different quantity units" {}))
    (let [base-attempts        (:attempts base)
          additional-measured  (filterv #(not (:warmup? %)) (:attempts additional))
          base-preparations    (:preparations base)
          base-preparation-ids (into #{} (map :preparation-id) base-preparations)
          used-preparation-ids (into #{} (keep :preparation-id) additional-measured)
          additional-preparations (filterv #(contains? used-preparation-ids
                                                       (:preparation-id %))
                                           (:preparations additional))]
      (when-let [duplicate (some #(contains? base-preparation-ids (:preparation-id %))
                                 additional-preparations)]
        (incompatible! "cannot merge runs with duplicate preparation ids"
                       {:preparation-id (:preparation-id duplicate)}))
      (let [offset    (count base-attempts)
            attempts  (into (vec base-attempts)
                            (map-indexed (fn [index attempt]
                                           (assoc attempt :attempt-id (+ offset index)))
                                         additional-measured))
            plan      (assoc (:plan base)
                             :repetitions (+ (get-in base [:plan :repetitions])
                                             (get-in additional [:plan :repetitions])))
            remaining (reduce consume-slot (initial-remaining plan) attempts)
            ended-at  (or ended-at
                          (let [base-end       (:ended-at base)
                                additional-end (:ended-at additional)]
                            (if (neg? (compare base-end additional-end))
                              additional-end
                              base-end)))
            metadata  (update (:metadata base) :diagnostics
                              #(assoc (or % {})
                                      :merge
                                      {:base-run-id (:run-id base)
                                       :additional-run-id (:run-id additional)
                                       :additional-warmups-excluded
                                       (count (filter :warmup? (:attempts additional)))
                                       :merged-at ended-at}))]
        (check-run {:type :run-record
                    :run-id run-id
                    :started-at (:started-at base)
                    :ended-at ended-at
                    :plan plan
                    :metadata metadata
                    :analysis (:analysis base)
                    :quantities (:quantities base)
                    :preparations (into (vec base-preparations) additional-preparations)
                    :attempts attempts
                    :case-outcomes (merge (:case-outcomes base) (:case-outcomes additional))
                    :remaining remaining
                    :termination {:reason :completed}})))))

(defn- check-feature-configuration
  "Requires evidence that matches each saved configuration's progress."
  [{:keys [status build run effective-features failure reason]}]
  (when (#{:ready :running :completed} status)
    (require-valid! (some? build) "feature comparison configuration needs a build" {:status status}))
  (when (#{:ready :completed} status)
    (require-valid! (some? effective-features)
                    "feature comparison configuration needs effective features" {:status status}))
  (when (#{:running :completed} status)
    (require-valid! (some? run) "feature comparison configuration needs a run" {:status status}))
  (when (= :completed status)
    (require-valid! (some? (:exit-code run))
                    "completed feature comparison configuration needs a run exit code" {})
    (require-valid! (zero? (:exit-code run))
                    "completed feature comparison configuration needs a successful run" {}))
  (when (= :failed status)
    (require-valid! (or (some? failure) (some? reason))
                    "failed feature comparison configuration needs a failure or reason" {}))
  (when (= :unattempted status)
    (require-valid! (some? reason)
                    "unattempted feature comparison configuration needs a reason" {})))

(defn- check-feature-edge
  "Requires a path for completed analysis and a reason for unattempted work."
  [{:keys [status path failure reason]}]
  (case status
    :completed
    (require-valid! (some? path) "completed feature comparison edge needs a report path" {})

    :failed
    (require-valid! (or (some? failure) (some? reason))
                    "failed feature comparison edge needs a failure or reason" {})

    :unattempted
    (require-valid! (some? reason)
                    "unattempted feature comparison edge needs a reason" {})

    nil))

(defn check-feature-comparison
  "Validates saved feature-comparison progress against its planned experiment.
  Configurations and edges must match the pure planner after status evidence is
  attached. Terminal records cannot retain active work, and completed work must
  retain its build, run, effective-feature and report evidence."
  [record]
  (require-valid! (sm/validate schema:feature-comparison record)
                  "invalid schema:feature-comparison" {})
  (let [features       (:features record)
        configurations (:configurations record)
        edges          (:edges record)
        expected       (feature-plan/plan features
                                          {:max-features (count features)
                                           :dry-run true
                                           :confirm true})
        terminal?      (contains? #{:completed :failed :interrupted} (:status record))
        active?        (comp #{:planned :building :ready :running} :status)]
    (require-valid! (= features (:features expected))
                    "feature comparison features disagree with normalized selection" {})
    (require-valid! (= (mapv #(select-keys % [:slug :selection]) configurations)
                       (:configurations expected))
                    "feature comparison configurations disagree with planned features" {})
    (require-valid! (= (mapv #(select-keys % [:from :to :feature]) edges)
                       (:edges expected))
                    "feature comparison edges disagree with planned configurations" {})
    (require-valid! (= (count (:cases record)) (count (set (map :id (:cases record)))))
                    "duplicate feature comparison case" {})
    (when terminal?
      (require-valid! (some? (:ended-at record))
                      "terminal feature comparison needs an end time" {})
      (require-valid! (not-any? active? configurations)
                      "terminal feature comparison still has active configurations" {})
      (require-valid! (not-any? active? edges)
                      "terminal feature comparison still has active edges" {}))
    (when-not terminal?
      (require-valid! (nil? (:ended-at record))
                      "unfinished feature comparison has an end time" {}))
    (when (= :planned (:status record))
      (require-valid! (every? (comp #{:planned} :status) configurations)
                      "planned feature comparison has started configurations" {})
      (require-valid! (every? (comp #{:planned} :status) edges)
                      "planned feature comparison has started edges" {}))
    (when (= :completed (:status record))
      (require-valid! (every? (comp #{:completed} :status) configurations)
                      "completed feature comparison has unfinished configurations" {})
      (require-valid! (every? (comp #{:completed} :status) edges)
                      "completed feature comparison has unfinished edges" {}))
    (doseq [configuration configurations]
      (check-feature-configuration configuration))
    (doseq [edge edges]
      (check-feature-edge edge)))
  record)

(defn check-record
  "Validates a run, comparison or feature-comparison at a Transit boundary."
  [record]
  (case (:type record)
    :run-record (check-run record)
    :comparison (do
                  (require-valid! (sm/validate schema:comparison record) "invalid schema:comparison" {})
                  record)
    :feature-comparison (check-feature-comparison record)
    (throw (ex-info "unsupported benchmark format" {:type ::unsupported-format}))))

(defn encode
  "Encodes a supported record with transit."
  [record]
  (codec/encode-str (check-record record)))

(defn decode
  "Decodes from transit and validates a supported record."
  [text]
  (check-record (transit/decode-str text)))
