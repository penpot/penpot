;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.summarize
  "Reports use saved definitions and explicit metrics from raw RunRecords.
  Warmups and failed attempts supply no observations. A completed attempt can
  supply valid, invalid or absent metrics independently. Unattempted slots remain
  separate. Batches and render slices never multiply observation counts."
  (:require
   [benches.render-wasm.report.analysis :as analysis]
   [benches.render-wasm.result :as result]))

(def source-coverage
  "Limits of compatibility checks on stored inputs and settings."
  "Recorded inputs and settings checked; runtime-helper identity is unverified. Git SHA and dirty state record provenance.")

(def interpretation
  "Scope of intervals and of renderer completion measurements."
  "Intervals are exploratory and conditional on observed runs. Caches can evolve between attempts; repeat whole runs to investigate findings. Full denotes renderer submission completion. Render-call totals measure synchronous elapsed calls, without exclusive CPU or GPU-completion claims.")

(defn case-attempts
  "Returns chronological attempts for an exact saved case identifier."
  [run case-id]
  (filterv #(= case-id (:case-id %)) (:attempts run)))

(defn metric-ids
  "Discovers dynamic metric identifiers in first-appearance order, including failures."
  [run case-id]
  (vec (distinct (mapcat (comp keys :metrics) (case-attempts run case-id)))))

(defn definitions
  "Returns every recorded definition for a metric, so drift remains visible."
  [run case-id metric-id]
  (->> (case-attempts run case-id)
       (keep #(get-in % [:metrics metric-id]))
       (map result/metric-definition)
       (distinct)
       (vec)))

(defn observation-definitions
  "Returns definitions only for valid, completed observations outside warmups."
  [run case-id metric-id]
  (->> (case-attempts run case-id)
       (remove :warmup?)
       (filter #(= :completed (:outcome %)))
       (keep #(get-in % [:metrics metric-id]))
       (remove :invalid-reason)
       (map result/metric-definition)
       (distinct)
       (vec)))

(defn observations
  "Returns valid values and distinct counts for a saved case/metric.
  :unattempted counts remaining measured repetitions; case :remaining also
  retains pending warmups. :warmup counts executed warmup attempts.
  Failed measured attempts count as failed even with partial metrics.
  Failed warmups count as warmup attempts."
  [run case-id metric-id]
  (reduce
   (fn [out {:keys [warmup? outcome metrics]}]
     (let [entry (get metrics metric-id)
           kind  (cond
                   warmup? :warmup
                   (= :failed outcome) :failed
                   (not (contains? metrics metric-id)) :absent
                   (:invalid-reason entry) :invalid
                   :else :valid)]
       (cond-> (update-in out [:counts kind] inc)
         (= kind :valid) (update :values conj (:value entry)))))
   {:values []
    :counts {:valid 0 :invalid 0 :absent 0 :failed 0
             :unattempted (get-in run [:remaining case-id :repetitions])
             :warmup 0}}
   (case-attempts run case-id)))

(defn summarize-metric
  "Computes statistics for one dynamic metric with its definitions and counts."
  ([run case-id metric-id analysis] (summarize-metric run case-id metric-id analysis false))
  ([run case-id metric-id analysis force?]
   (let [{:keys [values counts]} (observations run case-id metric-id)
         definitions (definitions run case-id metric-id)
         mixed? (> (count (observation-definitions run case-id metric-id)) 1)]
     {:definitions definitions
      :counts counts
      :mixed-definitions? mixed?
      :statistics (if (and mixed? (not force?))
                    {:reason :inconsistent-metric-definitions :count (count values)}
                    (analysis/statistics values analysis))})))

(defn summarize
  "Builds a portable report from a constructed or decoded RunRecord.
  The run must already satisfy the RunRecord contract. Reporting uses one
  resolved method.
  Overrides affect reporting without changing the stored RunRecord."
  ([run] (summarize run nil))
  ([run overrides]
   (let [analysis (analysis/resolve-settings (merge (:analysis run) overrides))]
     {:run-id (:run-id run)
      :metadata (:metadata run)
      :analysis analysis
      :termination (:termination run)
      :source-coverage source-coverage
      :interpretation interpretation
      :cases (mapv (fn [{:keys [id] :as definition}]
                     {:case-id id
                      :definition definition
                      :preparations (filterv #(= id (:case-id %)) (:preparations run))
                      :remaining (get-in run [:remaining id])
                      :metrics (into {} (map (fn [metric-id]
                                               [metric-id (summarize-metric run id metric-id analysis)]))
                                     (metric-ids run id))})
                   (get-in run [:plan :cases]))})))
