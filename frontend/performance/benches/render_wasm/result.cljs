;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.result
  "Raw benchmark records retain scheduled work and its chronological evidence.
  RunRecord schema3 stores a saved plan, provenance, preparations and attempts.
  A preparation owns a scene fingerprint and setup diagnostics. An attempt uses
  one scheduled slot, even on failure. Metric entries declare value, unit,
  semantics, observation basis and zero policy. Comparison schema2 stores an
  analysis of two saved runs. Everything is encoded with transit."
  (:require
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [benches.render-wasm.codec :as codec]
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
   [:encoding-version [:= 1]]
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
   [:build [:map [:functional map?] [:provenance {:optional true} map?]]]
   [:environment map?]
   [:scored? boolean?]
   [:diagnostics {:optional true} map?]])

(def schema:termination
  "A run's current state or final reason, with optional diagnostic details."
  [:map [:reason keyword?]])

(def schema:run
  "RunRecord schema3. Cases are saved definitions, independent of any registry.
  Build :functional holds compatibility settings; :provenance holds identities."
  [:map {:closed true}
   [:type [:= :run-record]]
   [:schema-version [:= 3]]
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
   [:metrics [:map-of :any schema:metric-report]]])

(def schema:provenance
  "Saved run identity, timestamps, metadata and termination for comparison."
  [:map {:closed true}
   [:run-id some?] [:started-at string?] [:ended-at [:maybe string?]]
   [:metadata schema:metadata] [:termination schema:termination]])

(def schema:comparison
  "Comparison schema2 retains resolved analysis, differences and per-side reports."
  [:map {:closed true}
   [:type [:= :comparison]]
   [:schema-version [:= 2]]
   [:analysis analysis/schema:settings]
   [:baseline schema:provenance]
   [:candidate schema:provenance]
   [:forced? boolean?]
   [:source-coverage string?]
   [:interpretation string?]
   [:pairs [:vector
            [:map {:closed true}
             [:explicit? boolean?]
             [:mismatches [:vector [:map {:closed true}
                                    [:field :any] [:baseline :any] [:candidate :any] [:waived? boolean?]]]]
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

(defn check-run
  "Validates schema3 and chronological accounting at a saved-data boundary.
  Unsupported versions fail without invoking a legacy reader or case registry."
  [run]
  (when-not (and (= :run-record (:type run)) (= 3 (:schema-version run)))
    (throw (ex-info "unsupported RunRecord format" {:type ::unsupported-format
                                                    :schema-version (:schema-version run)})))
  (require-valid! (sm/validate schema:run run) "invalid RunRecord" {})
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
    (when (= :completed (get-in run [:termination :reason]))
      (require-valid! (every? #(every? zero? (vals %)) (vals left))
                      "completed run still has unattempted slots" {})))
  run)

(defn create-run
  "Creates an empty raw run from externally supplied identities and facts.
  The plan defaults to 3 warmups, 10 repetitions and a 30000 ms attempt timeout.
  For each case, all warmup slots precede measured slots. Failed attempts consume
  their slot too; attempts retain chronological order across cases."
  [{:keys [run-id started-at plan metadata analysis]}]
  (let [plan (merge {:warmups 3 :repetitions 10 :timeout-policy {:attempt-ms 30000}} plan)]
    (check-run {:type :run-record :schema-version 3
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
  (when (= :completed (:reason termination))
    (require-valid! (every? #(every? zero? (vals %)) (vals (:remaining run)))
                    "completed run still has unattempted slots" {}))
  (assoc run :ended-at ended-at :termination termination))

(defn check-record
  "Validates a supported run or comparison at a Transit boundary."
  [record]
  (case (:type record)
    :run-record (check-run record)
    :comparison (do
                  (when-not (= 2 (:schema-version record))
                    (throw (ex-info "unsupported Comparison format" {:type ::unsupported-format})))
                  (require-valid! (sm/validate schema:comparison record) "invalid Comparison" {})
                  record)
    (throw (ex-info "unsupported benchmark format" {:type ::unsupported-format}))))

(defn encode
  "Encodes a supported record with transit."
  [record]
  (codec/encode-str (check-record record)))

(defn decode
  "Decodes from transit and validates a supported record."
  [text]
  (check-record (transit/decode-str text)))
