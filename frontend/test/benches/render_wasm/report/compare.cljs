;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.compare
  "Offline comparisons of paired cases from two saved RunRecords.

  By default, comparisons are made between matching cases with equal settings.
  It is however possible to to explicitly select pairs allowing different case
  definitions or to entirely deactivate compatibility checks."
  (:require
   [benches.render-wasm.report.analysis :as analysis]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
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
  "Resolves the prototype CLI's single A,B selector into the extensible pair list.
  A belongs to baseline; B belongs to candidate. Force is a separate option."
  [baseline candidate selector]
  (let [names (str/split selector #"," -1)]
    (when-not (and (= 2 (count names)) (every? seq names))
      (throw (ex-info "--compare-cases requires one exact A,B pair" {:type ::invalid-selector})))
    [[(resolve-case baseline (first names)) (resolve-case candidate (second names))]]))

(defn- difference
  "Returns one compatibility difference when recorded values disagree."
  [field baseline candidate waived?]
  (when (not= baseline candidate)
    {:field field :baseline baseline :candidate candidate :waived? waived?}))

(defn- preparations
  "Returns chronological preparation evidence for an exact saved case."
  [run id]
  (filterv #(= id (:case-id %)) (:preparations run)))

(defn- preparation-facts
  "Collects distinct recorded fingerprints or graphics, independent of prep IDs."
  [run id field]
  (set (map field (preparations run id))))

(defn- execution-settings
  "Selects declared execution inputs; recipe versions and text remain provenance."
  [definition]
  (select-keys definition [:scene :scene-seed :params :view :context :completion
                           :batch-size :preparation :operation]))

(defn- evidence-differences
  "Checks cross-run equality plus missing or varying preparation evidence."
  [field baseline candidate waived?]
  (concat
   (when-let [d (difference field baseline candidate waived?)] [d])
   (when (or (not= 1 (count baseline)) (not= 1 (count candidate)))
     [{:field [field :incomplete-or-varying-evidence]
       :baseline baseline :candidate candidate :waived? waived?}])))

(defn- pair-differences
  "Checks saved inputs, graphics, environment, build, sampling and metric definitions."
  [baseline candidate b c shared explicit?]
  (let [bdef (some #(when (= b (:id %)) %) (get-in baseline [:plan :cases]))
        cdef (some #(when (= c (:id %)) %) (get-in candidate [:plan :cases]))]
    (vec
     (concat
      (keep identity
            [(difference :execution (execution-settings bdef) (execution-settings cdef) explicit?)
             (difference :environment (get-in baseline [:metadata :environment])
                         (get-in candidate [:metadata :environment]) false)
             (difference :functional-build (get-in baseline [:metadata :build :functional])
                         (get-in candidate [:metadata :build :functional]) false)
             (difference :warmups (get-in baseline [:plan :warmups])
                         (get-in candidate [:plan :warmups]) false)
             (difference :timeout-policy (get-in baseline [:plan :timeout-policy])
                         (get-in candidate [:plan :timeout-policy]) false)
             (difference :scored? (get-in baseline [:metadata :scored?])
                         (get-in candidate [:metadata :scored?]) false)])
      (evidence-differences :fingerprint (preparation-facts baseline b :fingerprint)
                            (preparation-facts candidate c :fingerprint) explicit?)
      (evidence-differences :effective-graphics (preparation-facts baseline b :effective-graphics)
                            (preparation-facts candidate c :effective-graphics) false)
      (mapcat (fn [metric-id]
                (let [bdefs (summary/definitions baseline b metric-id)
                      cdefs (summary/definitions candidate c metric-id)]
                  (evidence-differences [:metric metric-id] (set bdefs) (set cdefs) false)))
              shared)))))

(defn- side-report
  "Reports one side with saved definitions, setup evidence and sample accounting."
  [run id shared analysis force]
  {:case-id id
   :definition (some #(when (= id (:id %)) %) (get-in run [:plan :cases]))
   :preparations (preparations run id)
   :remaining (get-in run [:remaining id])
   :metrics (into {} (map (fn [metric-id]
                            [metric-id (summary/summarize-metric run id metric-id analysis force)])) shared)})

(defn- compare-pair
  "Compares shared numeric observations after resolving the compatibility policy."
  [baseline candidate [b c] explicit? force analysis]
  (let [b          (resolve-case baseline b)
        c          (resolve-case candidate c)
        candidate-ids (set (summary/metric-ids candidate c))
        shared     (filterv #(contains? candidate-ids %) (summary/metric-ids baseline b))
        mismatches (pair-differences baseline candidate b c shared explicit?)]
    (when (empty? shared)
      (throw (ex-info "selected cases have no shared metric identifiers"
                      {:type ::no-shared-metrics :baseline b :candidate c})))
    (when (and (not force) (some #(not (:waived? %)) mismatches))
      (throw (ex-info "incompatible saved runs"
                      {:type ::incompatible :baseline b :candidate c :mismatches mismatches})))
    {:explicit? explicit?
     :mismatches mismatches
     :baseline (side-report baseline b shared analysis force)
     :candidate (side-report candidate c shared analysis force)
     :metrics (into {}
                    (map (fn [id]
                           [id (analysis/changes (:values (summary/observations baseline b id))
                                                 (:values (summary/observations candidate c id))
                                                 analysis)]))
                    shared)}))

(defn- provenance
  "Retains run identity and facts without using Git/run/time as compatibility gates."
  [run]
  (select-keys run [:run-id :started-at :ended-at :metadata :termination]))

(defn compare-runs
  "Creates a `:comparison` from constructed or decoded `:run-record`.

  Inputs must already satisfy the `:runrecord` contract. Encoding validates the
  returned comparison at the boundary.

  Default pairs are over identical identifiers. This can be overriden by picking
  explicit pairs, or by bypassing all checks.

  - `:pairs` accepts an extensible vector of explicit [baseline candidate] pairs.
  - `:force` allows comparing any two records, but obviusly only compares metrics
     common to both.
  - `:analysis` resolves from method defaults, independent of each run's defaults."
  ([baseline candidate] (compare-runs baseline candidate {}))
  ([baseline candidate {:keys [pairs force analysis]}]
   (let [explicit? (some? pairs)
         candidate-ids (set (map :id (get-in candidate [:plan :cases])))
         pairs     (or pairs (->> (get-in baseline [:plan :cases])
                                  (map :id)
                                  (filter #(contains? candidate-ids %))
                                  (mapv #(vector % %))))
         analysis  (analysis/resolve-settings analysis)]
     (when (or (empty? pairs) (not (every? #(and (vector? %) (= 2 (count %))) pairs)))
       (throw (ex-info "comparison needs saved case pairs"
                       {:type ::invalid-pairs
                        :baseline (mapv (comp result/case-name :id) (get-in baseline [:plan :cases]))
                        :candidate (mapv (comp result/case-name :id) (get-in candidate [:plan :cases]))})))
     {:type :comparison :schema-version 2 :analysis analysis
      :baseline (provenance baseline) :candidate (provenance candidate)
      :forced? (boolean force)
      :source-coverage summary/source-coverage :interpretation summary/interpretation
      :pairs (mapv #(compare-pair baseline candidate % explicit? force analysis) pairs)})))
