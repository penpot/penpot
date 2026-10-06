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
  {:id :saved/pan :scene :saved :scene-version 4 :scene-seed 42
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
   :fingerprint {:encoding-version 1 :algorithm :sha-256 :digest (apply str (repeat 64 "a"))}
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
                    {:confidence js/NaN} {:min-ci 0} {:method-version 2} {:unknown 1}]]
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

(t/deftest unknown-format-and-poisoned-accounting-refuse
  (let [run (run-with [1])]
    (doseq [bad [(assoc run :schema-version 2) {:schema-version 1}]]
      (t/is (= ::result/unsupported-format (:type (error-data #(result/decode (transit/encode-str bad)))))))
    (doseq [bad [(assoc-in run [:attempts 0 :ordinal] 3)
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
                      (result/finish-run "end" {:reason :completed})
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

(t/deftest portable-report-roundtrip-does-not-use-registry
  (let [run (run-with (vec (range 1 11)))
        transported (-> run result/encode result/decode)]
    (t/is (= (summary/summarize run) (summary/summarize transported)))
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

(t/deftest definition-drift-is-visible-and-force-compares-raw-values
  (let [run (assoc-in (run-with [1 2]) [:attempts 1 :metrics metric-id :basis]
                      {:kind :per-call-average :calls 2})
        report (summary/summarize run)
        forced (compare/compare-runs run run {:force true})]
    (t/is (= :inconsistent-metric-definitions (get-in report [:cases 0 :metrics metric-id :statistics :reason])))
    (t/is (= ::compare/incompatible (:type (error-data #(compare/compare-runs run run)))))
    (t/is (= 0 (get-in forced [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= 1.5 (get-in forced [:pairs 0 :baseline :metrics metric-id :statistics :median :value])))
    (t/is (true? (get-in forced [:pairs 0 :baseline :metrics metric-id :mixed-definitions?])))))

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
        candidate (run-with [2 3 4 5] {:definition (assoc saved-case :scene-version 99)
                                       :analysis {:seed 99}
                                       :facts (-> metadata
                                                  (assoc :git {:sha "other" :dirty true})
                                                  (assoc-in [:build :provenance :run-marker] "second"))})
        comparison (compare/compare-runs baseline (assoc candidate :run-id "other" :started-at "later"))]
    (t/is (= 2 (:schema-version comparison)))
    (t/is (= method/defaults (:analysis comparison)))
    (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (= 1.5 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= comparison (-> comparison result/encode result/decode)))))

(t/deftest strict-and-forced-compatibility
  (let [base (run-with [1 2])]
    (doseq [[field changed]
            [[:fingerprint (run-with [3] {:prep (assoc-in preparation [:fingerprint :digest] (apply str (repeat 64 "b")))})]
             [:execution (run-with [3] {:definition (assoc saved-case :operation {:steps 30})})]
             [:scored? (run-with [3] {:facts (assoc metadata :scored? false)})]
             [:execution (run-with [3] {:definition (assoc saved-case :batch-size 20)})]
             [:execution (run-with [3] {:definition (assoc saved-case :completion :call)})]
             [:execution (run-with [3] {:definition (assoc saved-case :completion :await)})]
             [:execution (run-with [3] {:definition (assoc saved-case :preparation {:version 2})})]
             [:environment (run-with [3] {:facts (assoc-in metadata [:environment :browser] "other")})]
             [:functional-build (run-with [3] {:facts (assoc-in metadata [:build :functional :features] #{:other})})]
             [:warmups (run-with [3] {:warmups 1})]
             [:effective-graphics (run-with [3] {:prep (assoc preparation :effective-graphics {:renderer "software" :software true})})]
             [:timeout-policy (assoc-in (run-with [3]) [:plan :timeout-policy :attempt-ms] 100)]]]
      (let [changed    (-> changed result/encode result/decode)
            rejected   (error-data #(compare/compare-runs base changed))
            forced     (compare/compare-runs base changed {:force true})
            mismatches (get-in forced [:pairs 0 :mismatches])
            text       (format/format-comparison forced)]
        (t/is (= ::compare/incompatible (:type rejected)))
        (t/is (:forced? forced))
        (t/is (= [field] (mapv :field mismatches)))
        (t/is (= (:mismatches rejected) mismatches))
        (t/is (every? #(false? (:waived? %)) mismatches))
        (t/is (str/includes? text "FORCED COMPARISON"))
        (t/is (str/includes? text "MISMATCHES"))
        (t/is (str/includes? text (str ":field " (pr-str field))))))))

(t/deftest explicit-pairs-keep-non-case-checks-and-exact-names
  (let [base           (run-with [1])
        other          (-> (run-with [2] {:definition (assoc saved-case :id :different/zoom :scene :different
                                                             :operation {:steps 8} :batch-size 20
                                                             :completion :call :preparation {:version 2})
                                          :prep (assoc-in preparation [:fingerprint :digest] (apply str (repeat 64 "b")))})
                           result/encode
                           result/decode)
        pairs          (compare/selector-pair base other "saved/pan,different/zoom")
        comparison     (compare/compare-runs base other {:pairs pairs})
        execution      (first (filter #(= :execution (:field %)) (get-in comparison [:pairs 0 :mismatches])))
        execution-keys [:scene :operation :batch-size :completion :preparation]]
    (t/is (= [[:saved/pan :different/zoom]] pairs))
    (t/is (= 1 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (every? :waived? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (= {:scene :saved :operation {:steps 3}
              :batch-size 1 :completion :render-full :preparation {}}
             (select-keys (:baseline execution) execution-keys)))
    (t/is (= {:scene :different :operation {:steps 8}
              :batch-size 20 :completion :call :preparation {:version 2}}
             (select-keys (:candidate execution) execution-keys)))
    (t/is (= ::compare/incompatible
             (:type (error-data #(compare/compare-runs base (assoc-in other [:metadata :environment :cpu] "other")
                                                       {:pairs pairs})))))
    (let [unscored   (assoc-in other [:metadata :scored?] false)
          rejected   (error-data #(compare/compare-runs base unscored {:pairs pairs}))
          forced     (compare/compare-runs base unscored {:pairs pairs :force true})
          mismatches (get-in forced [:pairs 0 :mismatches])
          text       (format/format-comparison forced)]
      (t/is (= ::compare/incompatible (:type rejected)))
      (t/is (:forced? forced))
      (t/is (= (:mismatches rejected) mismatches))
      (t/is (= [{:field :scored? :baseline true :candidate false :waived? false}]
               (vec (remove :waived? mismatches))))
      (t/is (str/includes? text "FORCED COMPARISON"))
      (t/is (str/includes? text "MISMATCHES"))
      (t/is (str/includes? text ":field :scored?")))
    (t/is (= ["saved/pan"] (:available (error-data #(compare/resolve-case base "pan")))))
    (t/is (= ::compare/invalid-selector (:type (error-data #(compare/selector-pair base other "saved/pan")))))))

(t/deftest force-preserves-metric-definitions-and-excludes-failed-values
  (let [base (run-with [1 :failed])
        changed (assoc-in (run-with [2]) [:attempts 0 :metrics metric-id]
                          (entry 2 {:unit :seconds :basis {:kind :per-call-average :calls 50}}))
        comparison (compare/compare-runs base changed {:force true})]
    (t/is (= 1 (get-in comparison [:pairs 0 :metrics metric-id :absolute :value])))
    (t/is (= :seconds (get-in comparison [:pairs 0 :candidate :metrics metric-id :definitions 0 :unit])))
    (t/is (= 1 (get-in comparison [:pairs 0 :baseline :metrics metric-id :counts :failed])))
    (t/is (= ::compare/no-shared-metrics
             (:type (error-data #(compare/compare-runs base
                                                       (assoc-in changed [:attempts 0 :metrics] {:other (entry 2)})
                                                       {:force true})))))))

(t/deftest comparison-boundary-validates-nested-evidence
  (let [run (run-with [1 2])
        comparison (compare/compare-runs run run)
        changes-path [:pairs 0 :metrics metric-id]
        report-path [:pairs 0 :baseline :metrics metric-id]]
    (doseq [changed [(assoc-in comparison (conj changes-path :absolute :value) js/NaN)
                     (assoc-in comparison (conj changes-path :absolute-ci) {:lower 2 :upper 1})
                     (assoc-in comparison (conj report-path :counts :valid) -1)
                     (assoc-in comparison (conj report-path :definitions 0 :basis :calls) 0)
                     (assoc-in comparison [:baseline :metadata :scored?] :unknown)]]
      (t/is (= ::result/invalid-record (:type (error-data #(result/decode (transit/encode-str changed)))))))
    (t/is (= ::result/unsupported-format
             (:type (error-data #(result/decode (transit/encode-str (assoc comparison :schema-version 1)))))))))

(t/deftest force-keeps-invalid-measurements-unusable
  (let [baseline (run-with [js/NaN js/Infinity :absent :failed] {:repetitions 5})
        candidate (run-with [2] {:facts (assoc-in metadata [:environment :cpu] "other")})
        comparison (compare/compare-runs baseline candidate {:force true})]
    (t/is (= {:valid 0 :invalid 2 :absent 1 :failed 1 :unattempted 1 :warmup 0}
             (get-in comparison [:pairs 0 :baseline :metrics metric-id :counts])))
    (t/is (= :no-valid-observations (get-in comparison [:pairs 0 :metrics metric-id :absolute :reason])))
    (t/is (= :no-valid-observations (get-in comparison [:pairs 0 :metrics metric-id :percentage-ci :reason])))
    (t/is (= comparison (result/decode (result/encode comparison))))))
