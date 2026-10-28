;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.compare
  "Offline comparisons of paired cases from two saved schema:run records.

  Matching case identifiers select default pairs; explicit pairs only select
  cases. Permissions acknowledge recorded differences without hiding them."
  (:require
   [benches.render-wasm.report.analysis :as analysis]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [clojure.set :as set]
   [clojure.string :as str]))

(defn resolve-case
  "Resolves an identifier or exact name only from the saved plan.
  Unknown or ambiguous selectors report the available names for that file."
  [run selector]
  (let [ids     (mapv :id (get-in run [:plan :cases]))
        matches (if (some #(= selector %) ids)
                  [selector]
                  (filterv #(= selector (result/case-name %)) ids))]
    (if (= 1 (count matches))
      (first matches)
      (throw (ex-info (str "cannot resolve saved case " (pr-str selector)
                           "; available cases: " (str/join ", " (map result/case-name ids)))
                      {:type ::unknown-case :selector selector :available (mapv result/case-name ids)})))))

(defn selector-pair
  "Resolves a single A,B comparison selector into an extensible pair list.
  A belongs to baseline; B belongs to candidate. Force is a separate option."
  [baseline candidate selector]
  (let [names (str/split selector #"," -1)]
    (when-not (and (= 2 (count names)) (every? seq names))
      (throw (ex-info "--cases requires one exact A,B pair" {:type ::invalid-selector})))
    [[(resolve-case baseline (first names)) (resolve-case candidate (second names))]]))

(defn- difference
  "Returns one compatibility difference when recorded values disagree."
  [field baseline candidate]
  (when (not= baseline candidate)
    {:field field :baseline baseline :candidate candidate :waived? false}))

(defn- preparations
  "Returns chronological preparation evidence for an exact saved case."
  [run id]
  (filterv #(= id (:case-id %)) (:preparations run)))

(defn- preparation-facts
  "Collects distinct recorded fingerprints or graphics, independent of prep IDs."
  [run id field]
  (set (map field (preparations run id))))

(defn- scene-settings
  "Selects scene identity and generation inputs, separate from execution."
  [definition]
  (select-keys definition [:scene :scene-seed :params]))

(defn- execution-settings
  "Selects operation and measurement settings that scene permission cannot ignore."
  [definition]
  (select-keys definition [:view :context :completion
                           :batch-size :preparation :operation]))

(defn- evidence-differences
  "Checks cross-run equality plus missing or varying preparation evidence."
  [field baseline candidate]
  (concat
   (when-let [d (difference field baseline candidate)] [d])
   (when (or (not= 1 (count baseline)) (not= 1 (count candidate)))
     [{:field [field :incomplete-or-varying-evidence]
       :baseline baseline :candidate candidate :waived? false}])))

(defn- feature-names?
  "Checks the vector-of-strings shape of recorded or expected feature names."
  [features]
  (and (vector? features) (every? string? features)))

(defn- get-effective-feature-evidence
  "Returns comparison values and eligibility for automatic feature acknowledgment.
  Missing, nil and explicitly empty features remain distinct. Collections compare
  as sets, but only saved vectors of strings qualify for automatic acknowledgment."
  [run]
  (let [features (get-in run [:metadata :build :functional :features] ::missing-features)]
    {:value (if (coll? features) (set features) features)
     :valid (feature-names? features)}))

(defn- pair-differences
  "Checks saved inputs, graphics, environment, build, sampling and metric definitions."
  [baseline candidate b c shared]
  (let [bdef (some #(when (= b (:id %)) %) (get-in baseline [:plan :cases]))
        cdef (some #(when (= c (:id %)) %) (get-in candidate [:plan :cases]))]
    (vec
     (concat
      (keep identity
            [(difference :scene (scene-settings bdef) (scene-settings cdef))
             (difference :execution (execution-settings bdef) (execution-settings cdef))
             (difference :environment (get-in baseline [:metadata :environment])
                         (get-in candidate [:metadata :environment]))
             (difference :effective-features (:value (get-effective-feature-evidence baseline))
                         (:value (get-effective-feature-evidence candidate)))
             (difference :functional-build (dissoc (get-in baseline [:metadata :build :functional]) :features)
                         (dissoc (get-in candidate [:metadata :build :functional]) :features))
             (difference :build-environment (get-in baseline [:metadata :build :environment])
                         (get-in candidate [:metadata :build :environment]))
             (difference :warmups (get-in baseline [:plan :warmups])
                         (get-in candidate [:plan :warmups]))
             (difference :timeout-policy (get-in baseline [:plan :timeout-policy])
                         (get-in candidate [:plan :timeout-policy]))
             (difference :scored? (get-in baseline [:metadata :scored?])
                         (get-in candidate [:metadata :scored?]))])
      (evidence-differences :fingerprint (preparation-facts baseline b :fingerprint)
                            (preparation-facts candidate c :fingerprint))
      (evidence-differences :effective-graphics (preparation-facts baseline b :effective-graphics)
                            (preparation-facts candidate c :effective-graphics))
      (mapcat (fn [metric-id]
                (let [bdefs (summary/definitions baseline b metric-id)
                      cdefs (summary/definitions candidate c metric-id)]
                  (evidence-differences [:metric metric-id] (set bdefs) (set cdefs))))
              shared)))))

(defn- feature-change-evidence?
  "Checks feature facts against saved effective features and requested selections.
  Both selections must record one off-to-on requested toggle with equal default
  policy. Effective transitive additions/removals remain unrestricted, but must
  match the supplied facts."
  [baseline candidate {:keys [feature baseline-features candidate-features added removed]}]
  (let [b         (get-effective-feature-evidence baseline)
        c         (get-effective-feature-evidence candidate)
        b-request (get-in baseline [:metadata :build :provenance :feature-selection])
        c-request (get-in candidate [:metadata :build :provenance :feature-selection])]
    (and (:valid b) (:valid c)
         (string? feature)
         (every? feature-names?
                 [baseline-features candidate-features added removed])
         (map? b-request) (map? c-request)
         (boolean? (:default-features? b-request))
         (boolean? (:default-features? c-request))
         (= (:default-features? b-request) (:default-features? c-request))
         (feature-names? (:features b-request))
         (feature-names? (:features c-request))
         (not (contains? (set (:features b-request)) feature))
         (= (conj (set (:features b-request)) feature) (set (:features c-request)))
         (= (:value b) (set baseline-features))
         (= (:value c) (set candidate-features))
         (= (set/difference (:value c) (:value b)) (set added))
         (= (set/difference (:value b) (:value c)) (set removed)))))

(defn- expected-feature-change?
  [baseline candidate expected]
  (and (feature-change-evidence? baseline candidate expected)
       (contains? (set (:added expected)) (:feature expected))))

(defn- acknowledge-difference
  "Retains a mismatch and records its applicable rule, if any.
  Automatic feature evidence and scoped permissions take precedence over all.
  Fingerprint content is scene data; incomplete/varying evidence is not."
  [{:keys [field] :as mismatch} permissions expected-feature-change?]
  (let [rule (cond
               (and (= :effective-features field) expected-feature-change?) :feature-toggle
               (and (:allow-build-differences permissions)
                    (contains? #{:effective-features :functional-build :build-environment} field)) :build
               (and (:allow-scene-differences permissions)
                    (contains? #{:scene :fingerprint} field)) :scene
               (:allow-all-differences permissions) :all)]
    (cond-> mismatch
      rule (assoc :waived? true :rule rule))))

(defn- side-report
  "Reports one side with saved definitions, setup evidence and sample accounting."
  [run id shared analysis allow-all-differences]
  {:case-id id
   :definition (some #(when (= id (:id %)) %) (get-in run [:plan :cases]))
   :preparations (preparations run id)
   :remaining (get-in run [:remaining id])
   :failed-attempts (summary/get-failed-attempts run id)
   :metrics (into {} (map (fn [metric-id]
                            [metric-id (summary/summarize-metric run id metric-id analysis allow-all-differences)])) shared)})

(defn- metric-changes
  "Keeps diagnostic changes unavailable for differing or varying definitions.
  Only all-differences permits comparing their valid raw values without conversion."
  [baseline candidate b c id mismatches allow-all-differences analysis]
  (if (and (not allow-all-differences)
           (some #(contains? #{[:metric id] [[:metric id] :incomplete-or-varying-evidence]}
                             (:field %)) mismatches))
    {:absolute {:reason :inconsistent-metric-definitions}
     :percentage {:reason :inconsistent-metric-definitions}
     :absolute-ci {:reason :inconsistent-metric-definitions}
     :percentage-ci {:reason :inconsistent-metric-definitions}}
    (analysis/changes (:values (summary/observations baseline b id))
                      (:values (summary/observations candidate c id)) analysis)))

(defn- compare-pair
  "Compares shared numeric observations after resolving the compatibility policy."
  [baseline candidate [b c] explicit? permissions expected-feature-change? on-unallowed-difference analysis]
  (let [b                     (resolve-case baseline b)
        c                     (resolve-case candidate c)
        candidate-ids         (set (summary/metric-ids candidate c))
        shared                (filterv #(contains? candidate-ids %) (summary/metric-ids baseline b))
        mismatches            (mapv #(acknowledge-difference % permissions expected-feature-change?)
                                    (pair-differences baseline candidate b c shared))
        allow-all-differences (:allow-all-differences permissions)]
    (when (empty? shared)
      (throw (ex-info "selected cases have no shared metric identifiers"
                      {:type ::no-shared-metrics :baseline b :candidate c})))
    (when (and (not= :record on-unallowed-difference) (some #(not (:waived? %)) mismatches))
      (throw (ex-info "incompatible saved runs"
                      {:type ::incompatible :baseline b :candidate c :mismatches mismatches})))
    {:explicit? explicit?
     :mismatches mismatches
     :baseline (side-report baseline b shared analysis allow-all-differences)
     :candidate (side-report candidate c shared analysis allow-all-differences)
     :metrics (into {}
                    (map (fn [id]
                           [id (metric-changes baseline candidate b c id mismatches
                                               allow-all-differences analysis)]))
                    shared)}))

(defn- provenance
  "Retains run identity and facts without using Git/run/time as compatibility gates."
  [run]
  (select-keys run [:run-id :started-at :ended-at :metadata :termination]))

(defn compare-runs
  "Creates a schema:comparison from constructed or decoded schema:run records.

  Inputs must already satisfy schema:run. Encoding validates the returned
  comparison at the boundary.

  Default pairs use identical identifiers. Explicit pairs select cases without
  changing compatibility checks.

  - `:pairs` accepts an extensible vector of explicit [baseline candidate] pairs.
  - `:allow-build-differences` acknowledges build settings and effective features.
  - `:allow-scene-differences` acknowledges scene inputs and fingerprint content.
    It never acknowledges incomplete or varying evidence or execution settings.
  - `:allow-all-differences` acknowledges any compatibility mismatch and permits
    mixed-definition statistics on valid raw observations, without conversions.
  Scoped permissions combine by union. Every mismatch retains its values,
  legacy `:waived?` field and, only when acknowledged, its `:rule`.
  - `:expected-feature-change` is planner evidence with string `:feature` and
    vectors `:baseline-features`, `:candidate-features`, `:added`, `:removed`.
    Automatic acknowledgment requires saved effective vectors of strings, exact
    sets/deltas with that feature added, and saved requested selections differing
    only by that toggle, with the same recorded default-feature policy.
  - `:edge` attaches checked feature-comparison context: `:from`, `:to`, and the five expected
    feature fields. When both options are supplied, their feature facts must agree.
    The ordinary encoding boundary validates the saved edge's shape.
  - Internal `:on-unallowed-difference :record` returns diagnostic reports instead
    of throwing for unacknowledged differences. It keeps strict statistics.
  - `:analysis` resolves from method defaults, independent of each run's defaults."
  ([baseline candidate] (compare-runs baseline candidate {}))
  ([baseline candidate {:keys [pairs allow-build-differences allow-scene-differences
                               allow-all-differences expected-feature-change edge
                               on-unallowed-difference analysis]}]
   (let [explicit?     (some? pairs)
         candidate-ids (set (map :id (get-in candidate [:plan :cases])))
         pairs         (or pairs (->> (get-in baseline [:plan :cases])
                                      (map :id)
                                      (filter #(contains? candidate-ids %))
                                      (mapv #(vector % %))))
         analysis      (analysis/resolve-settings analysis)
         permissions   {:allow-build-differences (boolean allow-build-differences)
                        :allow-scene-differences (boolean allow-scene-differences)
                        :allow-all-differences (boolean allow-all-differences)}
         expected?     (expected-feature-change? baseline candidate expected-feature-change)]
     (when (and (some? edge) (not (feature-change-evidence? baseline candidate edge)))
       (throw (ex-info "edge context disagrees with saved run feature evidence"
                       {:type ::invalid-edge :edge edge})))
     (when (and (some? edge) (some? expected-feature-change)
                (not= expected-feature-change
                      (select-keys edge [:feature :baseline-features :candidate-features :added :removed])))
       (throw (ex-info "edge context disagrees with expected feature facts"
                       {:type ::invalid-edge :edge edge :expected-feature-change expected-feature-change})))
     (when (or (empty? pairs) (not (every? #(and (vector? %) (= 2 (count %))) pairs)))
       (throw (ex-info "comparison needs saved case pairs"
                       {:type ::invalid-pairs
                        :baseline (mapv (comp result/case-name :id) (get-in baseline [:plan :cases]))
                        :candidate (mapv (comp result/case-name :id) (get-in candidate [:plan :cases]))})))
     (cond-> {:type :comparison :analysis analysis
              :baseline (provenance baseline) :candidate (provenance candidate)
              :permissions permissions
              :source-coverage summary/source-coverage :interpretation summary/interpretation
              :pairs (mapv #(compare-pair baseline candidate % explicit? permissions expected?
                                          on-unallowed-difference analysis) pairs)}
       (some? edge) (assoc :edge edge)))))
