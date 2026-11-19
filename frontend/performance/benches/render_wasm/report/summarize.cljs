;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.summarize
  "Offline analysis of raw command histories, grouped within phase executions."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.quantities :as quantities]
   [benches.render-wasm.report.analysis :as analysis]))

(def source-coverage
  "Recorded inputs and settings checked; runtime-helper identity is unverified. Git SHA and dirty state record provenance.")

(def interpretation
  "Intervals are exploratory and conditional on available executions. Incomplete and timed-out cases can select which observations survive. Caches can correlate attempts; repeat whole runs to investigate findings. Full denotes renderer submission completion. Sums combine only one metric type within one phase execution. Finite clock resolution can produce valid zero durations.")

(defn case-attempts
  "Returns chronological attempts for an exact saved case identifier."
  [run case-id]
  (filterv #(= case-id (:case-id %)) (:attempts run)))

(defn get-failed-attempts
  "Returns failed warmup and measured attempts with their saved evidence."
  [run case-id]
  (filterv #(= :failed (:outcome %)) (case-attempts run case-id)))

(defn executions
  "Returns actual phase executions with their eligibility and saved identities.

  Args:
    run: validated saved run.
    case-id: exact saved case identifier.
    phase: preparation, main or reset.

  Returns:
    Chronological phase histories with phase and preparation/attempt outcomes."
  [run case-id phase]
  (let [attempts (case-attempts run case-id)]
    (if (= :preparation phase)
      (let [definition (some #(when (= case-id (:id %)) %) (get-in run [:plan :cases]))]
        (mapv (fn [prep]
                (let [block (first (:phases prep))]
                  {:preparation-id (:preparation-id prep)
                   :events (:events block)
                   :warmup? (and (not= :reuse (:context definition)) (:warmup? prep))
                   :outcome (:outcome block)
                   :preparation-outcome (if (:failure prep) :failed :completed)}))
              (filter #(and (= case-id (:case-id %)) (seq (:phases %))) (:preparations run))))
      (into []
            (keep (fn [attempt]
                    (when-let [block (some #(when (= phase (:phase %)) %) (:phases attempt))]
                      (merge (select-keys attempt [:attempt-id :preparation-id :warmup?])
                             {:outcome (:outcome block) :attempt-outcome (:outcome attempt)
                              :events (:events block)}))))
            attempts))))

(defn phase-counts
  "Counts actual phase executions, including empty histories and excluded work.

  Args:
    run: validated saved run.
    case-id: exact saved case identifier.

  Returns:
    Execution, completion, failure and warmup counts for each phase."
  [run case-id]
  (into {}
        (map (fn [phase]
               (let [blocks (executions run case-id phase)]
                 [phase {:executions (count blocks)
                         :completed (count (filter #(= :completed (:outcome %)) blocks))
                         :failed (count (filter #(= :failed (:outcome %)) blocks))
                         :warmup (count (filter :warmup? blocks))}])))
        [:preparation :main :reset]))

(defn metric-ids
  "Returns [phase command-or-global metric-type] keys in first appearance order.

  Args:
    run: validated saved run.
    case-id: exact saved case identifier.

  Returns:
    Distinct report keys, including quantities recorded during failed work."
  [run case-id]
  (vec (distinct
        (for [phase [:preparation :main :reset]
              block (executions run case-id phase)
              event (:events block)
              metric (keys (:metrics event))
              scope [(:command event) :global]]
          [phase scope metric]))))

(defn definitions
  "Returns valid saved quantity descriptions for a report key.

  Args:
    run: validated saved run.
    _case-id: exact saved case identifier.
    id: vector of phase, command or global scope, and metric type.

  Returns:
    A vector containing the description, or an empty vector for invalid metadata."
  [run _case-id [_phase _scope metric]]
  (let [description (get-in run [:quantities metric])]
    (if (sm/validate quantities/schema:description description) [description] [])))

(defn- invalid-reason
  [value]
  (cond
    (not (js/Number.isFinite value)) :non-finite
    (neg? value) :negative
    :else nil))

(defn- execution-value
  [block scope metric]
  (let [events (->> (:events block)
                    (map-indexed vector)
                    (filter (fn [[_ event]]
                              (and (or (= :global scope) (= scope (:command event)))
                                   (contains? (:metrics event) metric)))))
        values (vec (mapcat #(get-in (second %) [:metrics metric]) events))
        invalid (vec (for [[event-index event] events
                           [value-index value] (map-indexed vector (get-in event [:metrics metric]))
                           :let [reason (invalid-reason value)]
                           :when reason]
                       (merge (select-keys block [:attempt-id :preparation-id])
                              {:event-index event-index :value-index value-index
                               :value value :reason reason})))
        total (when (and (seq values) (empty? invalid)) (reduce + 0 values))]
    {:invocations (count events) :raw-values (count values)
     :diagnostics (cond-> invalid
                    (and (some? total) (not (js/Number.isFinite total)))
                    (conj {:reason :non-finite-sum}))
     :value total}))

(defn observations
  "Sums one metric type per eligible phase execution.
  Invocation and raw-value counts cover eligible executions, including invalid
  values. Statistical n counts valid execution sums. Failed attempts exclude
  both main and reset. Successful preparation survives later attempt failures.

  Args:
    run: validated saved run.
    case-id: exact saved case identifier.
    id: vector of phase, command or global scope, and metric type.

  Returns:
    Valid execution sums, accounting counts and invalidity diagnostics."
  [run case-id [phase scope metric :as id]]
  (let [description (get-in run [:quantities metric])
        diagnostic (when (empty? (definitions run case-id id))
                     {:reason (if (contains? (:quantities run) metric)
                                :malformed-quantity :unknown-quantity)
                      :metric metric :description description})]
    (reduce
     (fn [out {:keys [warmup? outcome attempt-outcome preparation-outcome] :as block}]
       (let [{:keys [value invocations raw-values diagnostics]} (execution-value block scope metric)
             kind (cond
                    warmup? :warmup
                    (or (= :failed outcome) (= :failed attempt-outcome)
                        (= :failed preparation-outcome)) :failed
                    (zero? invocations) :absent
                    (or diagnostic (seq diagnostics)) :invalid
                    :else :valid)
             eligible? (not (#{:warmup :failed} kind))]
         (cond-> (update-in out [:counts kind] inc)
           eligible? (update-in [:counts :invocations] + invocations)
           eligible? (update-in [:counts :raw-values] + raw-values)
           eligible? (update :diagnostics into diagnostics)
           (= kind :valid) (update :values conj value))))
     {:values [] :diagnostics (if diagnostic [diagnostic] [])
      :counts {:valid 0 :invalid 0 :absent 0 :failed 0 :warmup 0
               :invocations 0 :raw-values 0
               :unattempted (if (= :preparation phase) 0
                                (get-in run [:remaining case-id :repetitions]))}}
     (executions run case-id phase))))

(defn summarize-metric
  "Computes statistics across execution sums for one phase, scope and metric type.

  Args:
    run: validated saved run.
    case-id: exact saved case identifier.
    id: vector of phase, command or global scope, and metric type.
    analysis: resolved statistical settings.

  Returns:
    Quantity descriptions, counts, diagnostics and statistics across executions."
  [run case-id id analysis]
  (let [{:keys [values counts diagnostics]} (observations run case-id id)]
    {:definitions (definitions run case-id id)
     :statistic :sum :counts counts :diagnostics diagnostics
     :statistics (analysis/statistics values analysis)}))

(defn case-report
  "Reports saved case facts and phase quantities using one analysis method.

  Args:
    run: validated saved run.
    id: exact saved case identifier.
    ids: selected report keys.
    analysis: resolved statistical settings.

  Returns:
    Case definition, raw preparation/failure evidence, counts and metric reports."
  [run id ids analysis]
  {:case-id id
   :definition (some #(when (= id (:id %)) %) (get-in run [:plan :cases]))
   :outcome (get-in run [:case-outcomes id])
   :preparations (filterv #(= id (:case-id %)) (:preparations run))
   :phases (phase-counts run id)
   :remaining (get-in run [:remaining id])
   :failed-attempts (get-failed-attempts run id)
   :metrics (into {} (map (fn [metric-id]
                            [metric-id (summarize-metric run id metric-id analysis)])) ids)})

(defn summarize
  "Reports a validated saved run using saved cases and quantity descriptions.

  Args:
    run: validated saved run.
    overrides: optional statistical settings overriding the saved defaults.

  Returns:
    A report with provenance, termination and separate analyses for each case."
  ([run] (summarize run nil))
  ([run overrides]
   (let [analysis (analysis/resolve-settings (merge (:analysis run) overrides))]
     {:run-id (:run-id run) :metadata (:metadata run) :analysis analysis
      :termination (:termination run)
      :source-coverage source-coverage :interpretation interpretation
      :cases (mapv (fn [{:keys [id]}]
                     (case-report run id (metric-ids run id) analysis))
                   (get-in run [:plan :cases]))})))
