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
   [benches.render-wasm.feature-plan :as feature-plan]
   [benches.render-wasm.report.analysis :as analysis]))

(def schema:basis
  "An attempt total or an average over a declared number of inner calls."
  [:or
   [:map {:closed true} [:kind [:= :attempt-total]]]
   [:map {:closed true} [:kind [:= :per-call-average]] [:calls [:int {:min 1}]]]])

(def schema:metric-definition
  "The declared meaning and observation basis of a metric.
  Authors define units and semantics; the schema accepts any vocabulary.
  Comparison checks their equality without interpreting or converting them."
  [:map {:closed true}
   [:unit :any]
   [:semantics :any]
   [:basis schema:basis]
   [:zero-policy [:enum :positive :allow]]])

(def schema:metric
  "An explicit observation, including raw invalid values and their reason."
  [:map {:closed true}
   [:value :any]
   [:unit :any]
   [:semantics :any]
   [:basis schema:basis]
   [:zero-policy [:enum :positive :allow]]
   [:invalid-reason [:maybe keyword?]]])

(def schema:fingerprint
  "The content digest of the actual validated starting snapshot."
  [:map {:closed true}
   [:algorithm [:= :sha-256]]
   [:digest [:re #"^[0-9a-f]{64}$"]]])

(def schema:preparation
  "Untimed setup evidence for one owned scene."
  [:map {:closed true}
   [:preparation-id some?]
   [:case-id some?]
   [:fingerprint schema:fingerprint]
   [:effective-graphics map?]
   [:diagnostics map?]])

(def schema:attempt
  "One chronological execution; failure consumes a slot and may retain evidence."
  [:map {:closed true}
   [:ordinal [:int {:min 0}]]
   [:case-id some?]
   [:preparation-id [:maybe some?]]
   [:warmup? boolean?]
   [:outcome [:enum :completed :failed]]
   [:metrics [:map-of :any schema:metric]]
   [:trace map?]
   [:failure {:optional true} map?]
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
   [:diagnostics {:optional true} map?]])

(def schema:termination
  "A run's current state or final reason, with optional diagnostic details."
  [:map [:reason keyword?]])

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
           [:timeout-policy [:map [:attempt-ms [:int {:min 1}]]]]]]
   [:metadata schema:metadata]
   [:analysis analysis/schema:settings]
   [:preparations [:vector schema:preparation]]
   [:attempts [:vector schema:attempt]]
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
  "Every metric definition, distinct counts and computed estimates."
  [:map {:closed true}
   [:definitions [:vector schema:metric-definition]]
   [:counts [:map {:closed true}
             [:valid [:int {:min 0}]] [:invalid [:int {:min 0}]] [:absent [:int {:min 0}]]
             [:failed [:int {:min 0}]] [:unattempted [:int {:min 0}]] [:warmup [:int {:min 0}]]]]
   [:mixed-definitions? boolean?]
   [:statistics schema:statistics]])

(def schema:comparison-side
  "One saved case's definition, preparations and shared metric reports."
  [:map {:closed true}
   [:case-id some?] [:definition [:map [:id some?]]]
   [:preparations [:vector schema:preparation]]
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
      [:failure {:optional true} :map]
      [:reason {:optional true} string?]]]]
   [:edges
    [:vector
     [:map
      [:from string?] [:to string?] [:feature string?]
      [:status [:enum :planned :completed :failed :unattempted]]
      [:path {:optional true} string?]
      [:failure {:optional true} :map]
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

(defn invalid-reason
  "Classifies one raw measurement without changing its value.
  Nil denotes an explicit missing value; an absent metric has no entry.
  Aggregate durations require positivity unless their entry permits zero."
  [{:keys [value zero-policy]}]
  (cond
    (nil? value) :missing-value
    (not (number? value)) :not-numeric
    (not (js/Number.isFinite value)) :non-finite
    (neg? value) :negative
    (and (zero? value) (= zero-policy :positive)) :below-resolution
    :else nil))

(defn metric
  "Validates an explicit metric definition and attaches its invalidity reason.
  Authored invalidity reasons survive when the numeric value otherwise qualifies."
  [entry]
  (let [entry (assoc entry :invalid-reason (or (invalid-reason entry)
                                               (:invalid-reason entry)))]
    (require-valid! (sm/validate schema:metric entry) "invalid metric entry" {:metric entry})
    entry))

(defn metric-definition
  "Returns unit, semantics, basis and zero policy without a measurement value."
  [entry]
  (select-keys entry [:unit :semantics :basis :zero-policy]))

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

(defn- check-completion
  "Requires all planned attempts to succeed before a run can be completed."
  [run termination]
  (when (= :completed (:reason termination))
    (require-valid! (every? #(every? zero? (vals %)) (vals (:remaining run)))
                    "completed run still has unattempted slots" {})
    (require-valid! (not-any? #(= :failed (:outcome %)) (:attempts run))
                    "completed run has failed attempts" {})))

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
    (doseq [[ordinal attempt] (map-indexed vector (:attempts run))]
      (require-valid! (= ordinal (:ordinal attempt)) "attempts are not chronological" {})
      (when-some [id (:preparation-id attempt)]
        (require-valid! (= (:case-id attempt) (:case-id (get prep-map id)))
                        "attempt references an unknown or different preparation" {:preparation-id id}))
      (require-valid! (or (= :failed (:outcome attempt)) (some? (:preparation-id attempt)))
                      "completed attempt needs a preparation" {})
      (doseq [[id entry] (:metrics attempt)]
        (require-valid! (= (:invalid-reason entry)
                           (or (invalid-reason entry) (:invalid-reason entry)))
                        "metric validity disagrees with raw value" {:metric-id id})))
    (check-completion run (:termination run)))
  run)

(defn create-run
  "Creates an empty raw run from externally supplied identities and facts.
  The plan defaults to 3 warmups, 10 repetitions and a 30000 ms attempt timeout.
  For each case, all warmup slots precede measured slots. Failed attempts consume
  their slot too; attempts retain chronological order across cases."
  [{:keys [run-id started-at plan metadata analysis]}]
  (let [plan (merge {:warmups 3 :repetitions 10 :timeout-policy {:attempt-ms 30000}} plan)]
    (check-run {:type :run-record
                :run-id run-id :started-at started-at :ended-at nil
                :plan plan :metadata metadata :analysis (analysis/resolve-settings analysis)
                :preparations [] :attempts [] :remaining (initial-remaining plan)
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
  (require-valid! (sm/validate schema:preparation preparation) "invalid preparation" {})
  (require-valid! (contains? (:remaining run) (:case-id preparation)) "unknown preparation case" {})
  (require-valid! (not-any? #(= (:preparation-id preparation) (:preparation-id %))
                            (:preparations run))
                  "duplicate preparation" {})
  (update run :preparations conj preparation))

(defn record-attempt
  "Checks and appends one new execution, consuming its scheduled slot.
  The run must come from create-run or decode, and metric entries from metric.
  Missing preparation is allowed for failures before scene setup completes.
  Earlier attempts are trusted; encode and decode check the complete record."
  [run attempt]
  (check-open! run)
  (let [attempt (-> (merge {:preparation-id nil :metrics {} :trace {}} attempt)
                    (assoc :ordinal (count (:attempts run))))]
    (require-valid! (sm/validate schema:attempt attempt) "invalid attempt" {})
    (when-some [id (:preparation-id attempt)]
      (let [preparation (some #(when (= id (:preparation-id %)) %) (:preparations run))]
        (require-valid! (= (:case-id attempt) (:case-id preparation))
                        "attempt references an unknown or different preparation" {:preparation-id id})))
    (require-valid! (or (= :failed (:outcome attempt)) (some? (:preparation-id attempt)))
                    "completed attempt needs a preparation" {})
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
