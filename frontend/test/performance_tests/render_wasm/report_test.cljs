;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.report-test
  "Pure tests for raw records, exploratory statistics and saved-run comparisons.
  Fixtures use saved case definitions without importing a registry or renderer."
  (:require
   [app.common.transit :as transit]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.report.analysis :as method]
   [benches.render-wasm.report.compare :as compare]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def metric-id [:custom/latency "arbitrary metric"])

(def saved-case
  "A saved definition that requires no current registration."
  {:id :saved/pan :scene :saved :scene-seed 42
   :params {:count 10} :view {:scale 1 :x 0 :y 0}
   :operation {:steps 3} :context :reuse :completion :render-full
   :batch-size 1 :preparation {}})

(def metadata
  "Functional build facts are separate from ephemeral build provenance."
  {:git {:sha "abc" :dirty false}
   :build {:functional {:mode :release :features #{:default} :env {"PROFILE" "0"}}
           :provenance {:run-marker "first"}}
   :environment {:browser "Chromium 1" :node "v22" :playwright "1" :os "Linux" :cpu "CPU"}
   :scored? true})

(def preparation
  "One content identity and its effective graphics."
  {:preparation-id :prep/one :case-id :saved/pan
   :fingerprint {:algorithm :sha-256 :digest (apply str (repeat 64 "a"))}
   :effective-graphics {:vendor "vendor" :renderer "hardware" :software false :dpr 2}
   :diagnostics {:upload-ms 123}})

(defn- entry
  "Declares a total duration; overrides supply explicit alternative definitions."
  ([value] (entry value {}))
  ([value overrides]
   (result/metric (merge {:value value :unit :ms :semantics :duration
                          :basis {:kind :attempt-total} :zero-policy :positive} overrides))))

(defn- error-data
  "Returns boundary error data, allowing tests to assert the refusal reason."
  [f]
  (try (f) nil (catch :default cause (ex-data cause))))

(defn- run-with
  "Creates a raw fixture with warmups and a fixed number of measured slots.
  :absent and :failed retain separate outcomes. Optional overrides change facts."
  ([values] (run-with values {}))
  ([values {:keys [definition prep facts analysis warmups repetitions]
            :or {definition saved-case prep preparation facts metadata warmups 0}}]
   (let [id (:id definition)
         prep (assoc prep :case-id id)
         run (-> (result/create-run {:run-id "run" :started-at "start"
                                     :plan {:cases [definition] :warmups warmups
                                            :repetitions (or repetitions (count values))}
                                     :metadata facts :analysis analysis})
                 (result/record-preparation prep))
         add (fn [run value warmup?]
               (result/record-attempt
                run {:case-id id :preparation-id (:preparation-id prep) :warmup? warmup?
                     :outcome (if (= value :failed) :failed :completed)
                     :metrics (if (#{:absent :failed} value) {} {metric-id (entry value)})
                     :trace {}}))]
     (reduce #(add %1 %2 false)
             (reduce #(add %1 %2 true) run (repeat warmups 1000)) values))))

(defn- run-with-features
  "Adds independent effective-feature evidence and a saved requested selection."
  [values features selection]
  (run-with values {:facts (-> metadata
                               (assoc-in [:build :functional :features] features)
                               (assoc-in [:build :provenance :feature-selection] selection))}))

(defn- approximately?
  "Compares computed numbers with a tolerance for floating point arithmetic."
  [a b]
  (< (js/Math.abs (- a b)) 1e-9))

(t/deftest mathematical-vectors
  (let [settings (method/resolve-settings {:min-p95 1 :min-p99 1})
        stats (method/statistics [1 2 3 4 10] settings)]
    (t/is (= 4 (get-in stats [:mean :value])))
    (t/is (= 3 (get-in stats [:median :value])))
    (t/is (= [1 10] [(get-in stats [:min :value]) (get-in stats [:max :value])]))
    (t/is (approximately? (js/Math.sqrt 12.5) (get-in stats [:sample-sd :value])))
    (t/is (= 1 (get-in stats [:mad :value])))
    (t/is (approximately? 8.8 (get-in stats [:p95 :value])))
    (t/is (approximately? 9.76 (get-in stats [:p99 :value])))
    (t/is (= 1.75 (method/quantile [4 2 1 3] 0.25)))
    (t/is (= 2.5 (method/quantile [1 2 3 4] 0.5)))
    (t/is (= [1 4] [(method/quantile [4 1 3 2] 0) (method/quantile [4 1 3 2] 1)]))))

(t/deftest numeric-arguments-throw-while-unavailable-results-retain-reasons
  (doseq [probability [-0.1 1.1 js/NaN js/Infinity js/-Infinity nil false "0.5"]]
    (t/is (= ::method/invalid-probability
             (:type (error-data #(method/quantile [1 2] probability))))))
  (doseq [values [[js/NaN] [js/Infinity] [js/-Infinity] [nil] ["1"] 42 {} #{1 2}]]
    (t/is (= ::method/invalid-observations
             (:type (error-data #(method/quantile values 0.5))))))
  (t/is (nil? (method/quantile [] 0.5)))
  (t/is (nil? (method/quantile nil 0.5)))
  (t/is (= ::method/invalid-settings
           (:type (error-data #(method/resolve-settings {:draws 0})))))
  (t/is (= {:reason :no-valid-observations} (:median (method/statistics [] method/defaults))))
  (t/is (= {:reason :insufficient-observations :minimum 10}
           (:median-ci (method/statistics [1] method/defaults)))))

(t/deftest calculated-numbers-and-overflow-have-independent-result-maps
  (let [stats   (method/statistics [4] method/defaults)
        changes (method/changes [1e-308] [1e308] (method/resolve-settings {:draws 5 :min-ci 1}))]
    (t/is (= {:value 4} (:median stats)))
    (t/is (= {:value 0} (:mad stats)))
    (t/is (= {:reason :insufficient-observations :minimum 2} (:sample-sd stats)))
    (t/is (= {:value 1e308} (:absolute changes)))
    (t/is (= {:reason :undefined-estimate} (:percentage changes)))
    (t/is (= {:lower 1e308 :upper 1e308} (:absolute-ci changes)))
    (t/is (= {:reason :undefined-bootstrap-estimate} (:percentage-ci changes)))))

(t/deftest empty-singleton-and-support-thresholds
  (let [settings (method/resolve-settings nil)]
    (t/is (= :no-valid-observations (get-in (method/statistics [] settings) [:median :reason])))
    (t/is (= :insufficient-observations (get-in (method/statistics [7] settings) [:sample-sd :reason])))
    (t/is (= 0 (get-in (method/statistics [7] settings) [:mad :value])))
    (t/is (= :insufficient-observations (get-in (method/statistics (vec (repeat 9 7)) settings) [:median-ci :reason])))
    (t/is (= {:lower 7 :upper 7} (:median-ci (method/statistics (vec (repeat 10 7)) settings))))
    (t/is (= :insufficient-observations (get-in (method/statistics (vec (repeat 99 7)) settings) [:p95 :reason])))
    (t/is (= {:value 7} (:p95 (method/statistics (vec (repeat 100 7)) settings))))
    (t/is (= :insufficient-observations (get-in (method/statistics (vec (repeat 499 7)) settings) [:p99 :reason])))
    (t/is (= {:value 7} (:p99 (method/statistics (vec (repeat 500 7)) settings))))))

(t/deftest analysis-settings-are-validated-and-recorded
  (doseq [override [{:draws 0} {:seed -1} {:seed 4294967296} {:confidence 1}
                    {:confidence js/NaN} {:min-ci 0} {:unknown 1}]]
    (t/is (= ::method/invalid-settings (:type (error-data #(method/resolve-settings override))))))
  (t/is (= 0 (:seed (method/resolve-settings {:seed 0}))))
  (t/is (= method/defaults (:analysis (run-with [1])))))

(t/deftest deterministic-bootstrap-and-independent-difference-vectors
  ;; Bounds also come from an independent Python Mulberry32 implementation.
  (let [settings (method/resolve-settings {:draws 100 :seed 123 :confidence 0.8})
        base (vec (range 1 11))
        stats (method/statistics base settings)
        changes (method/changes base (mapv #(+ 5 %) base) settings)]
    (t/is (= stats (method/statistics base settings)))
    (t/is (= 5.5 (get-in stats [:median :value])))
    (t/is (= {:lower 3.5 :upper 7} (:median-ci stats)))
    (t/is (= 5 (get-in changes [:absolute :value])))
    (t/is (approximately? 2.45 (get-in changes [:absolute-ci :lower])))
    (t/is (= 8 (get-in changes [:absolute-ci :upper])))
    (t/is (approximately? 31.201923076923077 (get-in changes [:percentage-ci :lower])))
    (t/is (approximately? 215.35714285714295 (get-in changes [:percentage-ci :upper])))
    (t/is (not= stats (method/statistics base (assoc settings :seed 0))))))

(t/deftest zero-baseline-percentages-and-undefined-draws
  (let [settings (method/resolve-settings {:draws 100 :min-ci 1 :seed 0})]
    (t/is (= :zero-baseline (get-in (method/changes [0 0] [1 2] settings) [:percentage :reason])))
    (t/is (= :zero-baseline (get-in (method/changes [0 0] [1 2] settings) [:percentage-ci :reason])))
    (t/is (= :zero-bootstrap-baseline (get-in (method/changes [0 1] [2 3] settings) [:percentage-ci :reason])))
    (t/is (= :no-valid-observations (get-in (method/changes [] [1] settings) [:absolute :reason])))))

(t/deftest partial-chronology-and-distinct-counts
  (let [run (run-with [1 :failed] {:repetitions 3 :warmups 1})
        ended (result/finish-run run "end" {:reason :interrupted})
        observations (summary/observations ended :saved/pan metric-id)]
    (t/is (= [0 1 2] (mapv :ordinal (:attempts ended))))
    (t/is (= {:warmups 0 :repetitions 1} (get-in ended [:remaining :saved/pan])))
    (t/is (= {:valid 1 :invalid 0 :absent 0 :failed 1 :unattempted 1 :warmup 1} (:counts observations)))
    (t/is (= [1] (:values observations)))
    (t/is (= ::result/invalid-record (:type (error-data #(result/finish-run run "end" {:reason :completed})))))
    (t/is (= ::result/invalid-record (:type (error-data #(result/record-attempt ended {})))))))

(t/deftest completed-runs-reject-failed-warmups-and-measured-attempts
  (doseq [failed-warmup? [true false]]
    (let [run     (-> (result/create-run {:run-id "failed" :started-at "start"
                                          :plan {:cases [saved-case] :warmups 1 :repetitions 1}
                                          :metadata metadata})
                      (result/record-preparation preparation)
                      (result/record-attempt {:case-id :saved/pan :preparation-id :prep/one
                                              :warmup? true
                                              :outcome (if failed-warmup? :failed :completed)
                                              :metrics {metric-id (entry 1000)} :trace {}})
                      (result/record-attempt {:case-id :saved/pan :preparation-id :prep/one
                                              :warmup? false
                                              :outcome (if failed-warmup? :completed :failed)
                                              :metrics {metric-id (entry 2)} :trace {}}))
          forged  (assoc run :ended-at "end" :termination {:reason :completed})
          failed  (result/finish-run run "end" {:reason :failed})]
      (t/is (= {:warmups 0 :repetitions 0} (get-in run [:remaining :saved/pan])))
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/finish-run run "end" {:reason :completed})))))
      (t/is (= ::result/invalid-record (:type (error-data #(result/check-run forged)))))
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/decode (transit/encode-str forged))))))
      (t/is (= failed (-> failed result/encode result/decode)))))
  (let [completed (result/finish-run (run-with [2] {:warmups 1}) "end" {:reason :completed})]
    (t/is (= completed (-> completed result/encode result/decode)))))

(t/deftest reports-show-failed-warmups-without-changing-measured-counts
  (let [failed     {:case-id :saved/pan :preparation-id :prep/one :warmup? true
                    :outcome :failed :metrics {metric-id (entry 1000)}
                    :trace {:render-slices [3]}
                    :failure {:stage :warmup :message "warmup render failed"}
                    :partial {:phase :render-full}}
        base       (-> (run-with [1 2] {:warmups 1})
                       (result/finish-run "end" {:reason :completed})
                       result/encode result/decode)
        other      (-> (result/create-run {:run-id "candidate" :started-at "start"
                                           :plan {:cases [saved-case] :warmups 1 :repetitions 2}
                                           :metadata metadata})
                       (result/record-preparation preparation)
                       (result/record-attempt failed)
                       (result/record-attempt {:case-id :saved/pan :preparation-id :prep/one
                                               :warmup? false :outcome :completed
                                               :metrics {metric-id (entry 2)} :trace {}})
                       (result/record-attempt {:case-id :saved/pan :preparation-id :prep/one
                                               :warmup? false :outcome :completed
                                               :metrics {metric-id (entry 4)} :trace {}})
                       (result/finish-run "end" {:reason :failed
                                                 :failure {:message "warmup render failed"}})
                       result/encode result/decode)
        report     (summary/summarize other)
        comparison (-> (compare/compare-runs base other) result/encode result/decode)
        counts     {:valid 2 :invalid 0 :absent 0 :failed 0 :unattempted 0 :warmup 1}
        evidence   [(assoc failed :ordinal 0)]
        text       (format/format-comparison comparison)]
    (t/is (= evidence (get-in report [:cases 0 :failed-attempts])))
    (t/is (= evidence (get-in comparison [:pairs 0 :candidate :failed-attempts])))
    (t/is (= counts (get-in report [:cases 0 :metrics metric-id :counts])))
    (t/is (= counts (get-in comparison [:pairs 0 :candidate :metrics metric-id :counts])))
    (t/is (= 3 (get-in comparison [:pairs 0 :candidate :metrics metric-id :statistics :median :value])))
    (t/is (str/includes? text "Baseline termination {:reason :completed}"))
    (t/is (str/includes? text "Candidate termination {:reason :failed"))
    (doseq [output [text (format/format-run report)]]
      (t/is (str/includes? output "failed-warmups=1"))
      (t/is (str/includes? output "warmup render failed"))
      (t/is (str/includes? output ":phase :render-full"))
      (t/is (str/includes? output ":render-slices [3]")))))

(t/deftest comparisons-display-running-interrupted-and-preparation-failure-status
  (let [base (-> (run-with [1] {:repetitions 2}) result/encode result/decode)]
    (doseq [termination [{:reason :running}
                         {:reason :interrupted :signal "SIGINT"}
                         {:reason :preparation-error :failure {:message "cannot prepare renderer"}}]]
      (let [other      (if (= :running (:reason termination))
                         base
                         (result/finish-run base "end" termination))
            comparison (-> (compare/compare-runs other other) result/encode result/decode)
            text       (format/format-comparison comparison)]
        (t/is (str/includes? text (str "Baseline termination " (pr-str termination))))
        (t/is (str/includes? text (str "Candidate termination " (pr-str termination))))
        (t/is (str/includes? text "remaining {:warmups 0, :repetitions 1}"))))
    (let [empty      (result/create-run {:run-id "empty" :started-at "start"
                                         :plan {:cases [saved-case] :warmups 0 :repetitions 2}
                                         :metadata metadata})
          stopped    (-> empty
                         (result/finish-run "end" {:reason :preparation-error
                                                   :failure {:message "cannot prepare renderer"}})
                         result/encode result/decode)
          report     (summary/summarize stopped)
          text       (format/format-run report)]
      (t/is (str/includes? text ":reason :preparation-error"))
      (t/is (str/includes? text "cannot prepare renderer"))
      (t/is (str/includes? text "remaining {:warmups 0, :repetitions 2}"))
      (t/is (empty? (get-in report [:cases 0 :metrics]))))))

(t/deftest invalid-missing-and-failed-evidence
  (let [run (run-with [1 js/NaN js/Infinity js/-Infinity 0 nil :absent :failed])
        obs (summary/observations run :saved/pan metric-id)]
    (t/is (= {:valid 1 :invalid 5 :absent 1 :failed 1 :unattempted 0 :warmup 0} (:counts obs)))
    (t/is (= [1] (:values obs)))
    (t/is (= :below-resolution (:invalid-reason (entry 0))))
    (t/is (nil? (:invalid-reason (entry 0 {:zero-policy :allow}))))
    (t/is (= :negative (:invalid-reason (entry -1 {:zero-policy :allow}))))
    (t/is (= :not-numeric (:invalid-reason (entry "bad"))))
    (t/is (= :missing-value (:invalid-reason (entry nil))))))

(t/deftest raw-transit-roundtrip-preserves-dynamic-identities-and-special-values
  (let [run (-> (run-with [js/NaN js/Infinity js/-Infinity nil] {:repetitions 5})
                (result/record-attempt {:case-id :saved/pan :warmup? false :outcome :failed
                                        :metrics {metric-id (entry 900)}
                                        :trace {:slices [{:duration-ms 0}]}
                                        :failure {:cause [{:message "failure"}]}
                                        :partial {:raw js/NaN}}))
        decoded (result/decode (result/encode run))]
    (t/is (js/Number.isNaN (get-in decoded [:attempts 0 :metrics metric-id :value])))
    (t/is (= js/Infinity (get-in decoded [:attempts 1 :metrics metric-id :value])))
    (t/is (= js/-Infinity (get-in decoded [:attempts 2 :metrics metric-id :value])))
    (t/is (nil? (get-in decoded [:attempts 3 :metrics metric-id :value])))
    (t/is (= {:slices [{:duration-ms 0}]} (get-in decoded [:attempts 4 :trace])))
    (t/is (js/Number.isNaN (get-in decoded [:attempts 4 :partial :raw])))
    (t/is (= {:cause [{:message "failure"}]} (get-in decoded [:attempts 4 :failure])))
    (t/is (= 1 (get-in (summary/observations decoded :saved/pan metric-id) [:counts :failed])))))

(t/deftest malformed-records-and-poisoned-accounting-refuse
  (let [run (run-with [1])]
    (t/is (= ::result/unsupported-format
             (:type (error-data #(result/decode (transit/encode-str (assoc run :type :unknown)))))))
    (doseq [bad [(dissoc run :plan)
                 (assoc run :attempts {})
                 (assoc-in run [:analysis :draws] 0)
                 (assoc-in run [:preparations 0 :fingerprint :algorithm] :unknown)
                 (assoc-in run [:preparations 0 :fingerprint :digest] "not a digest")
                 (assoc-in run [:attempts 0 :ordinal] 3)
                 (assoc-in run [:attempts 0 :preparation-id] :unknown)
                 (assoc-in run [:remaining :saved/pan :repetitions] 2)
                 (assoc-in run [:attempts 0 :metrics metric-id :value] js/NaN)]]
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/decode (transit/encode-str bad)))))))))

(t/deftest recording-keeps-preparation-links-and-failure-accounting
  (let [run     (run-with [] {:repetitions 2})
        attempt (measurement/attempt :saved/pan :prep/one false
                                     {:status "ok" :metrics {metric-id (entry 4)}})]
    (doseq [bad [(assoc preparation :case-id :unknown)
                 preparation]]
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/record-preparation run bad))))))
    (doseq [bad [(assoc attempt :preparation-id :unknown)
                 (assoc attempt :preparation-id nil)
                 (assoc attempt :case-id :unknown)]]
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/record-attempt run bad))))))
    (let [failed  (assoc attempt :preparation-id nil :outcome :failed)
          saved   (-> run
                      (result/record-attempt failed)
                      (result/record-attempt attempt)
                      (result/finish-run "end" {:reason :failed})
                      result/encode
                      result/decode)
          report  (summary/summarize saved)]
      (t/is (= {:warmups 0 :repetitions 0} (get-in saved [:remaining :saved/pan])))
      (t/is (= {:valid 1 :invalid 0 :absent 0 :failed 1 :unattempted 0 :warmup 0}
               (get-in report [:cases 0 :metrics metric-id :counts])))
      (t/is (= 4 (get-in report [:cases 0 :metrics metric-id :statistics :median :value])))
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/record-preparation saved (assoc preparation :preparation-id :prep/two)))))))))

(t/deftest standard-metrics-and-batches-use-one-observation
  (let [raw {:full-ms 12 :viewport-ready-ms 10
             :slices [{:duration-ms 0} {:duration-ms 2}]
             :cached-slices [{:duration-ms 3}] :settling-requested-ms 100}
        fresh (measurement/metrics raw true {:upload-ms 99})
        warm (measurement/metrics raw false {:upload-ms 99})
        batch (entry 2 {:basis {:kind :per-call-average :calls 200}})
        run (run-with [2])
        run (assoc-in run [:attempts 0 :metrics metric-id] batch)]
    (t/is (= 5 (get-in fresh [:renderer-call-total-ms :value])))
    (t/is (= 12 (get-in fresh [:first-render-ms :value])) "upload stays outside first render")
    (t/is (not (contains? fresh :full-ms)))
    (t/is (not (contains? warm :upload-ms)))
    (t/is (not (contains? warm :settling-requested-ms)))
    (t/is (= 1 (get-in (summary/summarize run) [:cases 0 :metrics metric-id :statistics :count])))
    (t/is (str/includes? (format/format-run (summary/summarize run)) ":per-call-average"))
    (t/is (js/Number.isNaN (measurement/render-call-total {:slices [{:duration-ms js/NaN}]})))))

(t/deftest render-call-evidence-distinguishes-missing-from-zero
  (let [raw [{}
             {:slices []}
             {:cached-slices []}
             {:slices [] :cached-slices []}
             {:slices [{:duration-ms 0}] :cached-slices []}
             {:slices [] :cached-slices [{:duration-ms 0}]}
             {:slices [{:frame-type "full"}]}]
        run (reduce (fn [run trace]
                      (result/record-attempt
                       run (measurement/attempt
                            :saved/pan :prep/one false
                            {:status "ok" :metrics (measurement/metrics trace false {})
                             :trace trace})))
                    (run-with [] {:repetitions (count raw)}) raw)
        saved (-> (result/finish-run run "end" {:reason :completed})
                  result/encode result/decode)
        metric (get-in (summary/summarize saved) [:cases 0 :metrics :renderer-call-total-ms])]
    (t/is (= raw (mapv :trace (:attempts saved))))
    (t/is (= {:valid 2 :invalid 4 :absent 1 :failed 0 :unattempted 0 :warmup 0}
             (:counts metric)))
    (t/is (= 2 (get-in metric [:statistics :count])))
    (t/is (= 0 (get-in metric [:statistics :median :value])))
    (doseq [ordinal [1 2 3 6]]
      (t/is (= :missing-value
               (get-in saved [:attempts ordinal :metrics :renderer-call-total-ms :invalid-reason]))))))

(t/deftest report-numbers-keep-integers-readable-and-small-values-precise
  (doseq [[value text] [[10000000 "10000000"]
                        [0.125 "0.125000"]
                        [0.000000125 "1.25000e-7"]]]
    (t/is (str/includes? (format/format-run (summary/summarize (run-with [value])))
                         (str " median " text " CI ")))))

(t/deftest warmup-order-and-interrupted-warmup-accounting
  (let [run (-> (result/create-run {:run-id "warmup" :started-at "start"
                                    :plan {:cases [saved-case] :warmups 3 :repetitions 2}
                                    :metadata metadata})
                (result/record-preparation preparation))
        attempt (measurement/attempt :saved/pan :prep/one true
                                     {:status "ok" :metrics {metric-id (entry 1)}})
        partial (-> run
                    (result/record-attempt (assoc attempt :outcome :failed))
                    (result/record-attempt attempt))
        ended (result/finish-run partial "end" {:reason :interrupted})
        saved (-> ended result/encode result/decode)
        case-summary (first (:cases (summary/summarize saved)))
        ready (result/record-attempt partial attempt)
        completed (result/record-attempt ready (assoc attempt :warmup? false))]
    (t/is (= ::result/invalid-record
             (:type (error-data #(result/record-attempt partial (assoc attempt :warmup? false))))))
    (t/is (= {:warmups 1 :repetitions 2} (:remaining case-summary)))
    (t/is (= {:valid 0 :invalid 0 :absent 0 :failed 0 :unattempted 2 :warmup 2}
             (get-in case-summary [:metrics metric-id :counts])))
    (t/is (= {:warmups 0 :repetitions 1} (get-in completed [:remaining :saved/pan])))
    (t/is (= ::result/invalid-record
             (:type (error-data #(result/record-attempt ready attempt)))))
    (let [reordered (assoc completed :attempts
                           (mapv (fn [ordinal attempt] (assoc attempt :ordinal ordinal))
                                 (range) (reverse (:attempts completed))))]
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/decode (transit/encode-str reordered)))))))))

(t/deftest records-roundtrip-summarize-and-compare-without-registry
  (let [run (run-with (vec (range 1 11)))
        transported (-> run result/encode result/decode)
        comparison (-> (compare/compare-runs transported transported)
                       result/encode result/decode)]
    (t/is (= (summary/summarize run) (summary/summarize transported)))
    (t/is (= 0 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (str/includes? (format/format-run (summary/summarize transported)) "runtime-helper identity is unverified"))
    (t/is (= 99 (get-in (summary/summarize run {:draws 99}) [:analysis :draws])))
    (t/is (= method/defaults (:analysis run)))))

(t/deftest all-dynamic-metric-identifiers-remain-distinct
  (let [ids [:dynamic/id "duration" 'custom/time [:operation 7] nil false]
        run (assoc-in (run-with [1]) [:attempts 0 :metrics]
                      (into {} (map (fn [id] [id (entry 1)])) ids))
        decoded (result/decode (result/encode run))
        comparison (compare/compare-runs decoded decoded)]
    (t/is (= (set ids) (set (keys (get-in (summary/summarize decoded) [:cases 0 :metrics])))))
    (t/is (= (set ids) (set (keys (get-in comparison [:pairs 0 :metrics])))))))

(t/deftest startup-and-incomplete-evidence-retain-unattempted-slots
  (let [empty (result/create-run {:run-id "empty" :started-at "start"
                                  :plan {:cases [saved-case] :warmups 0 :repetitions 3}
                                  :metadata metadata})
        stopped (result/finish-run empty "end" {:reason :startup-error :failure {:message "cannot launch"}})]
    (t/is (empty? (:attempts stopped)))
    (t/is (= {:warmups 0 :repetitions 3} (get-in stopped [:remaining :saved/pan])))
    (t/is (= stopped (result/decode (result/encode stopped))))))

(t/deftest completed-attempts-exclude-invalid-metrics-individually
  (let [run (assoc-in (run-with [1]) [:attempts 0 :metrics :bad] (entry js/NaN))
        report (summary/summarize run)]
    (t/is (= 1 (get-in report [:cases 0 :metrics metric-id :counts :valid])))
    (t/is (= 1 (get-in report [:cases 0 :metrics :bad :counts :invalid])))
    (t/is (= :no-valid-observations (get-in report [:cases 0 :metrics :bad :statistics :median :reason])))))

(t/deftest definition-drift-is-visible-and-all-differences-compares-raw-values
  (let [run        (assoc-in (run-with [1 2]) [:attempts 1 :metrics metric-id :basis]
                             {:kind :per-call-average :calls 2})
        report     (summary/summarize run)
        comparison (compare/compare-runs run run {:allow-all-differences true})]
    (t/is (= :inconsistent-metric-definitions (get-in report [:cases 0 :metrics metric-id :statistics :reason])))
    (t/is (= ::compare/incompatible (:type (error-data #(compare/compare-runs run run)))))
    (t/is (= 0 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= 1.5 (get-in comparison [:pairs 0 :baseline :metrics metric-id :statistics :median :value])))
    (t/is (true? (get-in comparison [:pairs 0 :baseline :metrics metric-id :mixed-definitions?])))))

(t/deftest failed-invalid-and-warmup-definitions-do-not-suppress-valid-statistics
  (let [run (-> (run-with [1 :failed js/NaN] {:warmups 1})
                (assoc-in [:attempts 0 :metrics metric-id] (entry 100 {:unit :seconds}))
                (assoc-in [:attempts 2 :metrics metric-id] (entry 100 {:unit :seconds}))
                (assoc-in [:attempts 3 :metrics metric-id] (entry js/NaN {:unit :seconds})))
        report (summary/summarize run)]
    (t/is (= 1 (get-in report [:cases 0 :metrics metric-id :statistics :median :value])))
    (t/is (= {:valid 1 :invalid 1 :absent 0 :failed 1 :unattempted 0 :warmup 1}
             (get-in report [:cases 0 :metrics metric-id :counts])))))

(t/deftest matching-cases-ignore-provenance-and-repetition-counts
  (let [baseline (run-with [1 2 3])
        candidate (run-with [2 3 4 5] {:definition (assoc saved-case :scene-description "Reworded description")
                                       :analysis {:seed 99}
                                       :facts (-> metadata
                                                  (assoc :git {:sha "other" :dirty true})
                                                  (assoc-in [:build :provenance :run-marker] "second"))})
        comparison (compare/compare-runs baseline (assoc candidate :run-id "other" :started-at "later"))]
    (t/is (= method/defaults (:analysis comparison)))
    (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (= 1.5 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= comparison (-> comparison result/encode result/decode)))))

(t/deftest strict-and-all-differences-compatibility
  (let [base (run-with [1 2])]
    (doseq [[field changed]
            [[:fingerprint (run-with [3] {:prep (assoc-in preparation [:fingerprint :digest] (apply str (repeat 64 "b")))})]
             [:execution (run-with [3] {:definition (assoc saved-case :operation {:steps 30})})]
             [:scored? (run-with [3] {:facts (assoc metadata :scored? false)})]
             [:execution (run-with [3] {:definition (assoc saved-case :batch-size 20)})]
             [:execution (run-with [3] {:definition (assoc saved-case :completion :call)})]
             [:execution (run-with [3] {:definition (assoc saved-case :completion :await)})]
             [:execution (run-with [3] {:definition (assoc saved-case :preparation {:settle-ms 25})})]
             [:environment (run-with [3] {:facts (assoc-in metadata [:environment :browser] "other")})]
             [:effective-features (run-with [3] {:facts (assoc-in metadata [:build :functional :features] #{:other})})]
             [:functional-build (run-with [3] {:facts (assoc-in metadata [:build :functional :mode] :debug)})]
             [:scene (run-with [3] {:definition (assoc saved-case :scene :other)})]
             [:scene (run-with [3] {:definition (assoc saved-case :scene-seed 43)})]
             [:scene (run-with [3] {:definition (assoc saved-case :params {:count 20})})]
             [:warmups (run-with [3] {:warmups 1})]
             [:effective-graphics (run-with [3] {:prep (assoc preparation :effective-graphics {:renderer "software" :software true})})]
             [:timeout-policy (assoc-in (run-with [3]) [:plan :timeout-policy :attempt-ms] 100)]]]
      (let [changed    (-> changed result/encode result/decode)
            rejected   (error-data #(compare/compare-runs base changed))
            selected   (error-data #(compare/compare-runs base changed {:pairs [[:saved/pan :saved/pan]]}))
            comparison (compare/compare-runs base changed {:allow-all-differences true})
            mismatches (get-in comparison [:pairs 0 :mismatches])
            text       (format/format-comparison comparison)]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (= rejected selected))
        (t/is (= {:allow-build-differences false :allow-scene-differences false :allow-all-differences true}
                 (:permissions comparison)))
        (t/is (= [field] (mapv :field mismatches)))
        (t/is (= (mapv #(assoc % :waived? true :rule :all) (:mismatches rejected)) mismatches))
        (t/is (every? #(and (false? (:waived? %)) (not (contains? % :rule))) (:mismatches rejected)))
        (t/is (str/includes? text "Permissions"))
        (t/is (str/includes? text "ACKNOWLEDGED DIFFERENCE"))
        (t/is (str/includes? text (str ":field " (pr-str field))))))))

(t/deftest explicit-pairs-select-cases-without-waiving-compatibility
  (let [base           (run-with [1])
        other          (-> (run-with [2] {:definition (assoc saved-case :id :different/zoom :scene :different
                                                             :operation {:steps 8} :batch-size 20
                                                             :completion :call :preparation {:settle-ms 25})
                                          :prep (assoc-in preparation [:fingerprint :digest] (apply str (repeat 64 "b")))})
                           result/encode
                           result/decode)
        pairs          (compare/selector-pair base other "saved/pan,different/zoom")
        rejected       (error-data #(compare/compare-runs base other {:pairs pairs}))
        comparison     (-> (compare/compare-runs base other {:pairs pairs :allow-all-differences true})
                           result/encode result/decode)
        execution      (first (filter #(= :execution (:field %)) (get-in comparison [:pairs 0 :mismatches])))
        execution-keys [:operation :batch-size :completion :preparation]]
    (t/is (= [[:saved/pan :different/zoom]] pairs))
    (t/is (= 1 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= ::compare/incompatible (:type rejected)))
    (t/is (= #{:scene :execution :fingerprint} (set (map :field (:mismatches rejected)))))
    (t/is (= (mapv #(assoc % :waived? true :rule :all) (:mismatches rejected))
             (get-in comparison [:pairs 0 :mismatches])))
    (t/is (every? :waived? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (str/includes? (format/format-comparison comparison) "ACKNOWLEDGED DIFFERENCE"))
    (t/is (str/includes? (format/format-comparison comparison) ":field :execution"))
    (t/is (str/includes? (format/format-comparison comparison) ":field :fingerprint"))
    (t/is (= {:operation {:steps 3}
              :batch-size 1 :completion :render-full :preparation {}}
             (select-keys (:baseline execution) execution-keys)))
    (t/is (= {:operation {:steps 8}
              :batch-size 20 :completion :call :preparation {:settle-ms 25}}
             (select-keys (:candidate execution) execution-keys)))
    (t/is (= ::compare/incompatible
             (:type (error-data #(compare/compare-runs base (assoc-in other [:metadata :environment :cpu] "other")
                                                       {:pairs pairs})))))
    (let [unscored   (assoc-in other [:metadata :scored?] false)
          rejected   (error-data #(compare/compare-runs base unscored {:pairs pairs}))
          comparison (compare/compare-runs base unscored {:pairs pairs :allow-all-differences true})
          mismatches (get-in comparison [:pairs 0 :mismatches])
          text       (format/format-comparison comparison)]
      (t/is (= ::compare/incompatible (:type rejected)))
      (t/is (true? (get-in comparison [:permissions :allow-all-differences])))
      (t/is (= (mapv #(assoc % :waived? true :rule :all) (:mismatches rejected)) mismatches))
      (t/is (= #{:scene :execution :fingerprint :scored?} (set (map :field mismatches))))
      (t/is (every? :waived? mismatches))
      (t/is (str/includes? text "ACKNOWLEDGED DIFFERENCE"))
      (t/is (str/includes? text ":field :scored?")))
    (t/is (= ["saved/pan"] (:available (error-data #(compare/resolve-case base "pan")))))
    (t/is (= ::compare/invalid-selector (:type (error-data #(compare/selector-pair base other "saved/pan")))))))

(t/deftest explicit-pairs-allow-unlike-names-with-matching-execution-and-fingerprints
  (let [base       (-> (run-with [1]) result/encode result/decode)
        other      (-> (run-with [2] {:definition (assoc saved-case :id :different/zoom)})
                       result/encode result/decode)
        comparison (-> (compare/compare-runs base other {:pairs [[:saved/pan :different/zoom]]})
                       result/encode result/decode)]
    (t/is (= {:allow-build-differences false :allow-scene-differences false :allow-all-differences false}
             (:permissions comparison)))
    (t/is (true? (get-in comparison [:pairs 0 :explicit?])))
    (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (= 1 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))))

(t/deftest all-differences-preserves-metric-definitions-and-excludes-failed-values
  (let [base (run-with [1 :failed])
        changed (assoc-in (run-with [2]) [:attempts 0 :metrics metric-id]
                          (entry 2 {:unit :seconds :basis {:kind :per-call-average :calls 50}}))
        comparison (compare/compare-runs base changed {:allow-all-differences true})]
    (t/is (= 1 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= :seconds (get-in comparison [:pairs 0 :candidate :metrics metric-id :definitions 0 :unit])))
    (t/is (= 1 (get-in comparison [:pairs 0 :baseline :metrics metric-id :counts :failed])))
    (t/is (= ::compare/no-shared-metrics
             (:type (error-data #(compare/compare-runs base
                                                       (assoc-in changed [:attempts 0 :metrics] {:other (entry 2)})
                                                       {:allow-all-differences true})))))))

(t/deftest comparison-boundary-validates-nested-evidence
  (let [run (run-with [1 2])
        comparison (compare/compare-runs run run)
        changes-path [:pairs 0 :metrics metric-id]
        report-path [:pairs 0 :baseline :metrics metric-id]]
    (doseq [changed [(assoc-in comparison (conj changes-path :absolute :value) js/NaN)
                     (assoc-in comparison (conj changes-path :absolute-ci) {:lower 2 :upper 1})
                     (assoc-in comparison (conj report-path :counts :valid) -1)
                     (assoc-in comparison (conj report-path :definitions 0 :basis :calls) 0)
                     (assoc-in comparison [:baseline :metadata :scored?] :unknown)
                     (dissoc comparison :pairs)
                     (assoc-in comparison [:analysis :confidence] 1)]]
      (t/is (= ::result/invalid-record (:type (error-data #(result/decode (transit/encode-str changed)))))))))

(t/deftest all-differences-keeps-invalid-measurements-unusable
  (let [baseline (run-with [js/NaN js/Infinity :absent :failed] {:repetitions 5})
        candidate (run-with [2] {:facts (assoc-in metadata [:environment :cpu] "other")})
        comparison (compare/compare-runs baseline candidate {:allow-all-differences true})]
    (t/is (= {:valid 0 :invalid 2 :absent 1 :failed 1 :unattempted 1 :warmup 0}
             (get-in comparison [:pairs 0 :baseline :metrics metric-id :counts])))
    (t/is (= :no-valid-observations (get-in comparison [:pairs 0 :metrics metric-id :absolute :reason])))
    (t/is (= :no-valid-observations (get-in comparison [:pairs 0 :metrics metric-id :percentage-ci :reason])))
    (t/is (= comparison (result/decode (result/encode comparison))))))

(t/deftest build-permission-acknowledges-only-recorded-build-differences
  (let [baseline (run-with [1])]
    (doseq [[key value field] [[:mode :debug :functional-build]
                               [:features ["other"] :effective-features]
                               [:default-features? false :functional-build]
                               [:instrumentation {:stats true} :functional-build]
                               [:env {"PROFILE" "1"} :functional-build]]]
      (let [candidate  (run-with [2] {:facts (assoc-in metadata [:build :functional key] value)})
            comparison (compare/compare-runs baseline candidate {:allow-build-differences true})
            mismatches (get-in comparison [:pairs 0 :mismatches])]
        (t/is (= ::compare/incompatible (:type (error-data #(compare/compare-runs baseline candidate)))))
        (t/is (= ::compare/incompatible
                 (:type (error-data #(compare/compare-runs baseline candidate {:allow-scene-differences true})))))
        (t/is (= [field] (mapv :field mismatches)))
        (t/is (every? #(and (:waived? %) (= :build (:rule %))) mismatches))
        (t/is (= {:allow-build-differences true :allow-scene-differences false :allow-all-differences false}
                 (:permissions comparison)))
        (t/is (= comparison (-> comparison result/encode result/decode)))))))

(t/deftest run-build-environment-validates-strings-and-preserves-absence
  (let [missing (-> (run-with [1]) result/encode result/decode)]
    (t/is (not (contains? (get-in missing [:metadata :build]) :environment)))
    (doseq [environment [{} {"CFLAGS" "" "RUSTFLAGS" "-C\u001fopt-level=3"}]]
      (let [run (run-with [1] {:facts (assoc-in metadata [:build :environment] environment)})]
        (t/is (= environment (get-in (-> run result/encode result/decode) [:metadata :build :environment])))))
    (doseq [environment [nil [] {:CFLAGS ""} {"CFLAGS" nil} {"CFLAGS" 0}]]
      (t/is (= ::result/invalid-record
               (:type (error-data #(result/decode
                                    (transit/encode-str
                                     (assoc-in missing [:metadata :build :environment] environment))))))))))

(t/deftest build-environment-differences-require-build-or-all-permission
  (doseq [[before after] [[::missing {}] [{} ::missing]
                          [{} {"CFLAGS" ""}]
                          [{"CFLAGS" "-O2"} {"CFLAGS" "-O3"}]]]
    (let [baseline  (run-with [1] {:facts (cond-> metadata
                                            (not= ::missing before) (assoc-in [:build :environment] before))})
          candidate (run-with [2] {:facts (cond-> metadata
                                            (not= ::missing after) (assoc-in [:build :environment] after))})]
      (t/is (empty? (get-in (compare/compare-runs baseline baseline) [:pairs 0 :mismatches])))
      (doseq [permissions [{} {:allow-scene-differences true}]]
        (t/is (= ::compare/incompatible
                 (:type (error-data #(compare/compare-runs baseline candidate permissions))))))
      (doseq [[permissions rule] [[{:allow-build-differences true} :build]
                                  [{:allow-all-differences true} :all]]]
        (let [comparison (compare/compare-runs baseline candidate permissions)]
          (t/is (= [{:field :build-environment
                     :baseline (when-not (= ::missing before) before)
                     :candidate (when-not (= ::missing after) after)
                     :waived? true :rule rule}]
                   (get-in comparison [:pairs 0 :mismatches])))
          (t/is (= comparison (-> comparison result/encode result/decode))))))))

(t/deftest feature-toggle-does-not-acknowledge-build-environment-changes
  (let [baseline   (assoc-in (run-with-features [1] [] {:default-features? false :features []})
                             [:metadata :build :environment] {"CFLAGS" "-O2"})
        candidate  (assoc-in (run-with-features [2] ["toggle"] {:default-features? false :features ["toggle"]})
                             [:metadata :build :environment] {"CFLAGS" "-O3"})
        expected   {:feature "toggle" :baseline-features [] :candidate-features ["toggle"]
                    :added ["toggle"] :removed []}
        diagnostic (compare/compare-runs baseline candidate {:expected-feature-change expected
                                                             :on-unallowed-difference :record})]
    (t/is (= ::compare/incompatible
             (:type (error-data #(compare/compare-runs baseline candidate {:expected-feature-change expected})))))
    (t/is (= [[:effective-features true :feature-toggle] [:build-environment false nil]]
             (mapv (juxt :field :waived? :rule) (get-in diagnostic [:pairs 0 :mismatches]))))
    (let [allowed (compare/compare-runs baseline candidate {:expected-feature-change expected
                                                            :allow-build-differences true})]
      (t/is (= [[:effective-features true :feature-toggle] [:build-environment true :build]]
               (mapv (juxt :field :waived? :rule) (get-in allowed [:pairs 0 :mismatches])))))))

(t/deftest scene-permission-acknowledges-scene-inputs-and-fingerprint-content
  (let [baseline (run-with [1])]
    (doseq [[field options] [[:scene {:definition (assoc saved-case :scene :other)}]
                             [:scene {:definition (assoc saved-case :scene-seed 43)}]
                             [:scene {:definition (assoc saved-case :params {:count 2000})}]
                             [:fingerprint {:prep (assoc-in preparation [:fingerprint :digest]
                                                            (apply str (repeat 64 "b")))}]]]
      (let [candidate  (run-with [2] options)
            comparison (compare/compare-runs baseline candidate {:allow-scene-differences true})
            mismatches (get-in comparison [:pairs 0 :mismatches])]
        (t/is (= ::compare/incompatible (:type (error-data #(compare/compare-runs baseline candidate)))))
        (t/is (= ::compare/incompatible
                 (:type (error-data #(compare/compare-runs baseline candidate {:allow-build-differences true})))))
        (t/is (= [field] (mapv :field mismatches)))
        (t/is (every? #(and (:waived? %) (= :scene (:rule %))) mismatches))
        (t/is (= 1 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
        (t/is (= comparison (-> comparison result/encode result/decode)))))))

(t/deftest build-and-scene-permissions-combine-by-union
  (let [baseline   (run-with [1])
        candidate  (run-with [2] {:definition (assoc saved-case :id :other/load :params {:count 2000})
                                  :facts (assoc-in metadata [:build :functional :mode] :debug)
                                  :prep (assoc-in preparation [:fingerprint :digest] (apply str (repeat 64 "b")))})
        pairs      [[:saved/pan :other/load]]
        options    {:pairs pairs :allow-build-differences true :allow-scene-differences true}
        comparison (compare/compare-runs baseline candidate options)
        mismatches (get-in comparison [:pairs 0 :mismatches])]
    (doseq [permissions [{} {:allow-build-differences true} {:allow-scene-differences true}]]
      (t/is (= ::compare/incompatible
               (:type (error-data #(compare/compare-runs baseline candidate (assoc permissions :pairs pairs)))))))
    (t/is (= #{[:scene :scene] [:functional-build :build] [:fingerprint :scene]}
             (set (map (juxt :field :rule) mismatches))))
    (t/is (every? :waived? mismatches))
    (t/is (= {:allow-build-differences true :allow-scene-differences true :allow-all-differences false}
             (:permissions comparison)))
    (t/is (= comparison (-> comparison result/encode result/decode)))))

(t/deftest scoped-permissions-retain-execution-runtime-and-measurement-checks
  (let [baseline (run-with [1])
        scoped   {:allow-build-differences true :allow-scene-differences true}]
    (doseq [[field candidate]
            [[:execution (run-with [2] {:definition (assoc saved-case :operation {:steps 30})})]
             [:execution (run-with [2] {:definition (assoc saved-case :operation {:steps 3 :args {:distance 20}})})]
             [:execution (run-with [2] {:definition (assoc saved-case :view {:scale 2 :x 0 :y 0})})]
             [:execution (run-with [2] {:definition (assoc saved-case :context :fresh)})]
             [:execution (run-with [2] {:definition (assoc saved-case :completion :call)})]
             [:execution (run-with [2] {:definition (assoc saved-case :batch-size 10)})]
             [:execution (run-with [2] {:definition (assoc saved-case :preparation {:settle-ms 25})})]
             [:environment (run-with [2] {:facts (assoc-in metadata [:environment :cpu] "other")})]
             [:effective-graphics (run-with [2] {:prep (assoc-in preparation [:effective-graphics :dpr] 1)})]
             [:warmups (run-with [2] {:warmups 1})]
             [:timeout-policy (assoc-in (run-with [2]) [:plan :timeout-policy :attempt-ms] 100)]
             [:scored? (run-with [2] {:facts (assoc metadata :scored? false)})]
             [[:metric metric-id] (assoc-in (run-with [2]) [:attempts 0 :metrics metric-id :unit] :seconds)]]]
      (let [candidate  (-> candidate result/encode result/decode)
            rejected   (error-data #(compare/compare-runs baseline candidate scoped))
            comparison (compare/compare-runs baseline candidate (assoc scoped :allow-all-differences true))]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (= [field] (mapv :field (:mismatches rejected))))
        (t/is (every? #(and (false? (:waived? %)) (not (contains? % :rule))) (:mismatches rejected)))
        (t/is (= (mapv #(assoc % :waived? true :rule :all) (:mismatches rejected))
                 (get-in comparison [:pairs 0 :mismatches])))))))

(t/deftest scene-permission-never-waives-incomplete-or-varying-evidence
  (let [baseline (run-with [1])
        varying  (result/record-preparation
                  (run-with [2])
                  (-> preparation
                      (assoc :preparation-id :prep/two)
                      (assoc-in [:fingerprint :digest] (apply str (repeat 64 "b")))))
        missing  (-> (run-with [:failed])
                     (assoc :preparations [])
                     (assoc-in [:attempts 0 :preparation-id] nil)
                     (assoc-in [:attempts 0 :metrics metric-id] (entry 2)))
        scoped   {:allow-scene-differences true :allow-build-differences true}]
    (doseq [candidate [varying missing]]
      (let [candidate  (-> candidate result/encode result/decode)
            rejected   (error-data #(compare/compare-runs baseline candidate scoped))
            diagnostic (compare/compare-runs baseline candidate (assoc scoped :on-unallowed-difference :record))
            mismatches (get-in diagnostic [:pairs 0 :mismatches])
            evidence   (first (filter #(= [:fingerprint :incomplete-or-varying-evidence] (:field %)) mismatches))]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (= (:mismatches rejected) mismatches))
        (t/is (false? (:waived? evidence)))
        (t/is (not (contains? evidence :rule)))
        (t/is (= :scene (:rule (first (filter #(= :fingerprint (:field %)) mismatches)))))
        (t/is (every? :waived?
                      (get-in (compare/compare-runs baseline candidate {:allow-all-differences true})
                              [:pairs 0 :mismatches])))
        (t/is (= diagnostic (-> diagnostic result/encode result/decode)))))))

(t/deftest diagnostic-record-retains-unacknowledged-differences-and-selected-rules
  (let [baseline   (run-with [1])
        candidate  (run-with [2] {:definition (assoc saved-case :params {:count 2000} :operation {:steps 30})
                                  :facts (assoc-in metadata [:build :functional :mode] :debug)})
        options    {:allow-build-differences true :on-unallowed-difference :record}
        diagnostic (compare/compare-runs baseline candidate options)
        mismatches (get-in diagnostic [:pairs 0 :mismatches])
        text       (format/format-comparison diagnostic)]
    (t/is (= (:mismatches (error-data #(compare/compare-runs baseline candidate (dissoc options :on-unallowed-difference))))
             mismatches))
    (t/is (= [[:scene false nil] [:execution false nil] [:functional-build true :build]]
             (mapv (juxt :field :waived? :rule) mismatches)))
    (t/is (= 1 (get-in diagnostic [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (str/includes? text "Permissions {:allow-build-differences true"))
    (t/is (str/includes? text "ACKNOWLEDGED DIFFERENCE {:field :functional-build"))
    (t/is (str/includes? text "UNACKNOWLEDGED DIFFERENCE {:field :scene"))
    (t/is (str/includes? text ":rule :build"))
    (t/is (= diagnostic (-> diagnostic result/encode result/decode)))))

(t/deftest diagnostic-record-keeps-strict-definition-statistics
  (let [baseline          (run-with [1 2])
        mixed             (assoc-in (run-with [3 4]) [:attempts 1 :metrics metric-id :basis]
                                    {:kind :per-call-average :calls 2})
        differing         (assoc-in (run-with [3 4]) [:attempts 0 :metrics metric-id :unit] :seconds)
        single-definition (-> (run-with [3 4])
                              (assoc-in [:attempts 0 :metrics metric-id :unit] :seconds)
                              (assoc-in [:attempts 1 :metrics metric-id :unit] :seconds))]
    (doseq [candidate [mixed differing single-definition]]
      (let [diagnostic (compare/compare-runs baseline candidate {:allow-build-differences true
                                                                 :allow-scene-differences true
                                                                 :on-unallowed-difference :record})
            allowed    (compare/compare-runs baseline candidate {:allow-all-differences true})
            changes    (get-in diagnostic [:pairs 0 :metrics metric-id])]
        (t/is (every? #(= {:reason :inconsistent-metric-definitions} %) (vals changes)))
        (t/is (= 2 (get-in diagnostic [:pairs 0 :candidate :metrics metric-id :counts :valid])))
        (t/is (every? #(false? (:waived? %)) (get-in diagnostic [:pairs 0 :mismatches])))
        (t/is (= 2 (get-in allowed [:pairs 0 :metrics metric-id :absolute :value])))
        (t/is (= 3.5 (get-in allowed [:pairs 0 :candidate :metrics metric-id :statistics :median :value])))
        (if (= candidate single-definition)
          (t/is (= 3.5 (get-in diagnostic [:pairs 0 :candidate :metrics metric-id :statistics :median :value])))
          (t/is (= :inconsistent-metric-definitions
                   (get-in diagnostic [:pairs 0 :candidate :metrics metric-id :statistics :reason]))))
        (t/is (= diagnostic (-> diagnostic result/encode result/decode)))))))

(t/deftest feature-toggle-acknowledges-only-exact-effective-feature-evidence
  (let [baseline   (run-with-features [1] ["base" "old"] {:default-features? false :features ["base"]})
        candidate  (run-with-features [2] ["base" "toggle" "transitive"]
                                      {:default-features? false :features ["base" "toggle"]})
        expected   {:feature "toggle" :baseline-features ["base" "old"]
                    :candidate-features ["base" "toggle" "transitive"]
                    :added ["toggle" "transitive"] :removed ["old"]}
        edge       (assoc expected :from "0" :to "1")
        comparison (compare/compare-runs baseline candidate {:expected-feature-change expected :edge edge})
        mismatch   (get-in comparison [:pairs 0 :mismatches 0])]
    (t/is (= :effective-features (:field mismatch)))
    (t/is (true? (:waived? mismatch)))
    (t/is (= :feature-toggle (:rule mismatch)))
    (t/is (= edge (:edge comparison)))
    (t/is (= {:allow-build-differences false :allow-scene-differences false :allow-all-differences false}
             (:permissions comparison)))
    (t/is (= comparison (-> comparison result/encode result/decode)))
    (doseq [invalid [nil
                     (assoc expected :feature "base")
                     (assoc expected :feature :toggle)
                     (assoc expected :baseline-features ["base"])
                     (assoc expected :candidate-features ["base" "toggle"])
                     (assoc expected :added ["toggle"])
                     (assoc expected :removed [])
                     (assoc expected :added #{"toggle" "transitive"})
                     (dissoc expected :baseline-features)]]
      (let [options    {:expected-feature-change invalid}
            rejected   (error-data #(compare/compare-runs baseline candidate options))
            diagnostic (compare/compare-runs baseline candidate (assoc options :on-unallowed-difference :record))]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (false? (get-in diagnostic [:pairs 0 :mismatches 0 :waived?])))
        (t/is (not (contains? (get-in diagnostic [:pairs 0 :mismatches 0]) :rule)))))
    (let [changed    (-> candidate
                         (assoc-in [:metadata :build :functional :mode] :debug)
                         (assoc-in [:plan :cases 0 :params] {:count 2000})
                         (assoc-in [:plan :cases 0 :operation] {:steps 30}))
          diagnostic (compare/compare-runs baseline changed {:expected-feature-change expected
                                                             :on-unallowed-difference :record})]
      (t/is (= [[:scene false nil] [:execution false nil]
                [:effective-features true :feature-toggle] [:functional-build false nil]]
               (mapv (juxt :field :waived? :rule) (get-in diagnostic [:pairs 0 :mismatches])))))))

(t/deftest edge-context-roundtrips-and-labels-identical-effective-sets
  (let [run        (run-with-features [1] ["base" "alias"] {:default-features? false :features ["base"]})
        candidate  (run-with-features [2] ["alias" "base"] {:default-features? false :features ["base" "toggle"]})
        expected   {:feature "toggle" :baseline-features ["base" "alias"]
                    :candidate-features ["alias" "base"] :added [] :removed []}
        edge       (assoc expected :from "0" :to "1")
        comparison (compare/compare-runs run candidate {:expected-feature-change expected :edge edge})]
    (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (= edge (:edge comparison)))
    (t/is (= comparison (-> comparison result/encode result/decode)))
    (t/is (str/includes? (format/format-comparison comparison) "Edge 0 -> 1 feature toggle"))
    (t/is (str/includes? (format/format-comparison comparison) "effective feature sets identical"))
    (let [contradictory (assoc edge :candidate-features ["base" "alias" "toggle"] :added ["toggle"])]
      (doseq [opts [{:edge contradictory}
                    {:edge contradictory
                     :expected-feature-change (dissoc contradictory :from :to)}]]
        (t/is (= ::compare/invalid-edge
                 (:type (error-data #(compare/compare-runs run candidate opts)))))))))

(t/deftest comparison-schema-validates-permissions-rules-and-edge-context
  (let [baseline   (run-with [1])
        candidate  (run-with [2] {:facts (assoc-in metadata [:build :functional :mode] :debug)})
        comparison (compare/compare-runs baseline candidate {:allow-build-differences true})
        edge       {:from "0" :to "1" :feature "toggle" :baseline-features []
                    :candidate-features ["toggle"] :added ["toggle"] :removed []}]
    (doseq [changed [(dissoc comparison :permissions)
                     (assoc-in comparison [:permissions :allow-build-differences] :yes)
                     (update comparison :permissions dissoc :allow-scene-differences)
                     (assoc comparison :forced? true)
                     (assoc-in comparison [:pairs 0 :mismatches 0 :rule] :other)
                     (assoc-in comparison [:pairs 0 :mismatches 0 :rule] nil)
                     (assoc-in comparison [:pairs 0 :mismatches 0 :waived?] false)
                     (update-in comparison [:pairs 0 :mismatches 0] dissoc :rule)
                     (assoc comparison :edge (dissoc edge :removed))
                     (assoc comparison :edge (assoc edge :feature :toggle))
                     (assoc comparison :edge (assoc edge :added #{"toggle"}))]]
      (t/is (= ::result/invalid-record (:type (error-data #(result/decode (transit/encode-str changed)))))))
    (doseq [rule [:feature-toggle :build :scene :all]]
      (let [record (-> comparison (assoc :edge edge) (assoc-in [:pairs 0 :mismatches 0 :rule] rule))]
        (t/is (= record (-> record result/encode result/decode)))
        (t/is (= ::result/invalid-record
                 (:type (error-data #(result/decode (transit/encode-str
                                                     (update-in record [:pairs 0 :mismatches 0] dissoc :rule)))))))))))

(t/deftest every-permission-keeps-invalid-measurements-and-no-shared-metrics-unusable
  (let [baseline  (run-with [js/NaN js/Infinity 0 -1 :absent :failed] {:warmups 1 :repetitions 7})
        candidate (run-with [2] {:warmups 1})]
    (doseq [permissions [{}
                         {:allow-build-differences true}
                         {:allow-scene-differences true}
                         {:allow-build-differences true :allow-scene-differences true}
                         {:allow-all-differences true}
                         {:on-unallowed-difference :record}]]
      (let [comparison (compare/compare-runs baseline candidate permissions)]
        (t/is (= {:valid 0 :invalid 4 :absent 1 :failed 1 :unattempted 1 :warmup 1}
                 (get-in comparison [:pairs 0 :baseline :metrics metric-id :counts])))
        (t/is (= :no-valid-observations (get-in comparison [:pairs 0 :metrics metric-id :absolute :reason])))
        (t/is (= :no-valid-observations (get-in comparison [:pairs 0 :metrics metric-id :percentage-ci :reason])))
        (t/is (= comparison (-> comparison result/encode result/decode)))
        (t/is (= ::compare/no-shared-metrics
                 (:type (error-data #(compare/compare-runs baseline
                                                           (update candidate :attempts
                                                                   (fn [attempts]
                                                                     (mapv (fn [attempt]
                                                                             (assoc attempt :metrics {:other (entry 2)})) attempts)))
                                                           permissions)))))))))

(t/deftest missing-nil-and-empty-effective-feature-evidence-stay-distinct-through-transit
  (let [empty-run (run-with-features [1] [] {:default-features? false :features []})
        missing   (-> empty-run
                      (update-in [:metadata :build :functional] dissoc :features)
                      result/encode result/decode)
        nil-run   (-> empty-run
                      (assoc-in [:metadata :build :functional :features] nil)
                      result/encode result/decode)]
    (t/is (empty? (get-in (compare/compare-runs empty-run empty-run) [:pairs 0 :mismatches])))
    (doseq [[baseline candidate bfeatures cfeatures]
            [[missing empty-run ::compare/missing-features #{}]
             [nil-run empty-run nil #{}]
             [missing nil-run ::compare/missing-features nil]
             [empty-run missing #{} ::compare/missing-features]
             [empty-run nil-run #{} nil]]]
      (let [rejected   (error-data #(compare/compare-runs baseline candidate))
            diagnostic (compare/compare-runs baseline candidate {:on-unallowed-difference :record})
            mismatch   {:field :effective-features :baseline bfeatures :candidate cfeatures :waived? false}]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (= [mismatch] (:mismatches rejected)))
        (t/is (= [mismatch] (get-in diagnostic [:pairs 0 :mismatches])))
        (t/is (= diagnostic (-> diagnostic result/encode result/decode)))))))

(t/deftest automatic-feature-acknowledgment-requires-saved-effective-vectors-of-strings
  (let [baseline  (run-with-features [1] [] {:default-features? false :features []})
        candidate (run-with-features [2] ["toggle"] {:default-features? false :features ["toggle"]})
        expected  {:feature "toggle" :baseline-features [] :candidate-features ["toggle"]
                   :added ["toggle"] :removed []}]
    (t/is (= :feature-toggle
             (get-in (compare/compare-runs baseline candidate {:expected-feature-change expected})
                     [:pairs 0 :mismatches 0 :rule])))
    (doseq [[b c] [[(update-in baseline [:metadata :build :functional] dissoc :features) candidate]
                   [(assoc-in baseline [:metadata :build :functional :features] nil) candidate]
                   [(assoc-in baseline [:metadata :build :functional :features] #{}) candidate]
                   [baseline (assoc-in candidate [:metadata :build :functional :features] #{"toggle"})]
                   [baseline (assoc-in candidate [:metadata :build :functional :features] [:toggle])]]]
      (let [b          (-> b result/encode result/decode)
            c          (-> c result/encode result/decode)
            options    {:expected-feature-change expected}
            rejected   (error-data #(compare/compare-runs b c options))
            diagnostic (compare/compare-runs b c (assoc options :on-unallowed-difference :record))]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (= [:effective-features] (mapv :field (get-in diagnostic [:pairs 0 :mismatches]))))
        (t/is (false? (get-in diagnostic [:pairs 0 :mismatches 0 :waived?])))
        (t/is (not (contains? (get-in diagnostic [:pairs 0 :mismatches 0]) :rule)))
        (t/is (= diagnostic (-> diagnostic result/encode result/decode)))))))

(t/deftest automatic-feature-acknowledgment-checks-saved-requested-selections
  (let [b-request {:default-features? false :features ["base"]}
        c-request {:default-features? false :features ["base" "toggle"]}
        expected  {:feature "toggle" :baseline-features ["base" "old"]
                   :candidate-features ["base" "toggle" "transitive"]
                   :added ["toggle" "transitive"] :removed ["old"]}]
    (doseq [default-features? [false true]]
      (let [baseline   (run-with-features [1] (:baseline-features expected)
                                          (assoc b-request :default-features? default-features?))
            candidate  (run-with-features [2] (:candidate-features expected)
                                          {:default-features? default-features? :features ["toggle" "base"]})
            comparison (compare/compare-runs (-> baseline result/encode result/decode)
                                             (-> candidate result/encode result/decode)
                                             {:expected-feature-change expected})]
        (t/is (= [:feature-toggle] (mapv :rule (get-in comparison [:pairs 0 :mismatches]))))
        (t/is (every? :waived? (get-in comparison [:pairs 0 :mismatches])))))
    (doseq [[b c] [[::missing c-request]
                   [b-request ::missing]
                   [nil c-request]
                   [b-request nil]
                   [{} c-request]
                   [b-request {}]
                   [[] c-request]
                   [(dissoc b-request :default-features?) c-request]
                   [b-request (dissoc c-request :default-features?)]
                   [(assoc b-request :default-features? :false) c-request]
                   [b-request (assoc c-request :default-features? true)]
                   [(dissoc b-request :features) c-request]
                   [b-request (dissoc c-request :features)]
                   [(assoc b-request :features #{"base"}) c-request]
                   [b-request (assoc c-request :features ["base" :toggle])]
                   [c-request c-request]
                   [b-request b-request]
                   [b-request (assoc c-request :features ["base" "extra" "toggle"])]
                   [(assoc b-request :features ["base" "old"]) c-request]]]
      (let [baseline   (cond-> (run-with-features [1] (:baseline-features expected) b)
                         (= ::missing b) (update-in [:metadata :build :provenance] dissoc :feature-selection))
            candidate  (cond-> (run-with-features [2] (:candidate-features expected) c)
                         (= ::missing c) (update-in [:metadata :build :provenance] dissoc :feature-selection))
            baseline   (-> baseline result/encode result/decode)
            candidate  (-> candidate result/encode result/decode)
            options    {:expected-feature-change expected}
            rejected   (error-data #(compare/compare-runs baseline candidate options))
            diagnostic (compare/compare-runs baseline candidate (assoc options :on-unallowed-difference :record))]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (= [:effective-features] (mapv :field (get-in diagnostic [:pairs 0 :mismatches]))))
        (t/is (false? (get-in diagnostic [:pairs 0 :mismatches 0 :waived?])))
        (t/is (not (contains? (get-in diagnostic [:pairs 0 :mismatches 0]) :rule)))
        (t/is (= diagnostic (-> diagnostic result/encode result/decode)))))))

(t/deftest edge-option-retains-context-and-rejects-conflicting-expected-facts
  (let [baseline   (run-with-features [1] [] {:default-features? false :features []})
        candidate  (run-with-features [2] ["toggle" "transitive"] {:default-features? false :features ["toggle"]})
        expected   {:feature "toggle" :baseline-features [] :candidate-features ["toggle" "transitive"]
                    :added ["toggle" "transitive"] :removed []}
        edge       (assoc expected :from "0" :to "1")
        comparison (compare/compare-runs baseline candidate {:expected-feature-change expected :edge edge})]
    (t/is (= edge (:edge comparison)))
    (t/is (= comparison (-> comparison result/encode result/decode)))
    (t/is (str/includes? (format/format-comparison comparison) "Edge 0 -> 1 feature toggle"))
    (t/is (not (str/includes? (format/format-comparison comparison) "effective feature sets identical")))
    (doseq [changed [(assoc edge :feature "other")
                     (assoc edge :baseline-features ["base"])
                     (assoc edge :candidate-features ["toggle"])
                     (assoc edge :added ["toggle"])
                     (assoc edge :removed ["base"])]]
      (t/is (= ::compare/invalid-edge
               (:type (error-data #(compare/compare-runs baseline candidate
                                                         {:expected-feature-change expected :edge changed
                                                          :on-unallowed-difference :record}))))))))
