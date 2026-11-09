;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.report-test
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

(def saved-case
  {:id :saved/pan :scene :saved :scene-seed 42 :params {:count 10}
   :view {:scale 1 :x 0 :y 0} :context :reuse :preparation {}})

(def metadata
  {:git {:sha "abc" :dirty false}
   :build {:functional {:mode :release :features []}}
   :environment {:browser "Chromium" :node "v24"} :scored? true})

(defn- event
  [command values]
  {:command command :metrics {:command/elapsed values}})

(defn- block
  [phase prep events]
  {:phase phase :preparation-id prep :outcome :completed :events events})

(defn- preparation
  [id events]
  {:preparation-id id :case-id :saved/pan :warmup? false
   :fingerprint {:algorithm :sha-256 :digest (apply str (repeat 64 "a"))}
   :effective-graphics {:renderer "hardware"}
   :diagnostics {:upload-ms 123}
   :phases [(block :preparation id events)]})

(defn- new-run
  [warmups repetitions context]
  (result/create-run {:run-id "run" :started-at "start"
                      :plan {:cases [(assoc saved-case :context context)]
                             :warmups warmups :repetitions repetitions}
                      :metadata metadata :analysis {:draws 50}}))

(defn- add-attempt
  [run prep warmup? outcome phases]
  (result/record-attempt
   run (measurement/attempt :saved/pan prep warmup?
                            (cond-> {:status (if (= :completed outcome) "ok" "failed")
                                     :phases phases}
                              (= :failed outcome) (assoc :phase (name (:phase (last phases)))
                                                         :message "renderer failed")))))

(defn- sample-run
  [values]
  (reduce (fn [run value]
            (add-attempt run :prep false :completed
                         [(block :main :prep [(event :test/pan [value])])]))
          (result/record-preparation (new-run 0 (count values) :reuse)
                                     (preparation :prep [])) values))

(defn- error-type
  [f]
  (try (f) nil (catch :default cause (:type (ex-data cause)))))

(def main-pan [:main :test/pan :command/elapsed])
(def main-global [:main :global :command/elapsed])

(defn- approximately?
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
             (error-type #(method/quantile [1 2] probability)))))
  (doseq [values [[js/NaN] [js/Infinity] [js/-Infinity] [nil] ["1"] 42 {} #{1 2}]]
    (t/is (= ::method/invalid-observations (error-type #(method/quantile values 0.5)))))
  (doseq [override [{:draws 0} {:seed -1} {:seed 4294967296} {:confidence 1}
                    {:confidence js/NaN} {:min-ci 0} {:unknown 1}]]
    (t/is (= ::method/invalid-settings (error-type #(method/resolve-settings override)))))
  (t/is (nil? (method/quantile [] 0.5)))
  (t/is (nil? (method/quantile nil 0.5)))
  (t/is (= {:reason :no-valid-observations} (:median (method/statistics [] method/defaults)))))

(t/deftest calculated-numbers-and-overflow-have-independent-result-maps
  (let [stats (method/statistics [4] method/defaults)
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

(t/deftest deterministic-bootstrap-and-independent-difference-vectors
  (let [settings (method/resolve-settings {:draws 100 :seed 123 :confidence 0.8})
        stats (method/statistics (vec (range 1 11)) settings)
        changes (method/changes (vec (range 1 11)) (vec (range 6 16)) settings)]
    (t/is (= 5.5 (get-in stats [:median :value])))
    (t/is (= {:lower 3.5 :upper 7} (:median-ci stats)))
    (t/is (= 5 (get-in changes [:absolute :value])))
    (t/is (approximately? 2.45 (get-in changes [:absolute-ci :lower])))
    (t/is (= 8 (get-in changes [:absolute-ci :upper])))
    (t/is (approximately? 31.201923076923077 (get-in changes [:percentage-ci :lower])))
    (t/is (approximately? 215.35714285714295 (get-in changes [:percentage-ci :upper])))
    (t/is (= stats (method/statistics (vec (range 1 11)) settings)))))

(t/deftest zero-baseline-percentages-and-undefined-draws
  (let [settings (method/resolve-settings {:draws 100 :min-ci 1 :seed 0})]
    (t/is (= :zero-baseline (get-in (method/changes [0 0] [1 2] settings) [:percentage :reason])))
    (t/is (= :zero-baseline (get-in (method/changes [0 0] [1 2] settings) [:percentage-ci :reason])))
    (t/is (= :zero-bootstrap-baseline (get-in (method/changes [0 1] [2 3] settings) [:percentage-ci :reason])))
    (t/is (= :no-valid-observations (get-in (method/changes [] [1] settings) [:absolute :reason])))))

(t/deftest repeated-commands-keep-invocations-values-and-statistical-n-distinct
  (let [phases [(block :main :prep
                       [(assoc-in (event :test/pan [2]) [:metrics :renderer/call-elapsed] [1 2])
                        (assoc-in (event :test/zoom [4]) [:metrics :renderer/call-elapsed] [4])
                        (assoc-in (event :test/pan [3]) [:metrics :renderer/call-elapsed] [3])])]
        run (-> (new-run 1 2 :reuse)
                (result/record-preparation (preparation :prep []))
                (add-attempt :prep true :completed phases)
                (add-attempt :prep false :completed phases)
                (add-attempt :prep false :completed phases)
                (result/record-case-outcome :saved/pan {:outcome :completed})
                (result/finish-run "end" {:reason :completed})
                result/encode result/decode)
        report (first (:cases (summary/summarize run)))
        pan (get-in report [:metrics [:main :test/pan :renderer/call-elapsed]])
        global (get-in report [:metrics [:main :global :renderer/call-elapsed]])]
    (t/is (= 3 (count (get-in run [:attempts 1 :phases 0 :events]))))
    (t/is (= {:valid 2 :invalid 0 :absent 0 :failed 0 :warmup 1 :unattempted 0
              :invocations 4 :raw-values 6} (:counts pan)))
    (t/is (= 2 (get-in pan [:statistics :count])))
    (t/is (= 6 (get-in pan [:statistics :median :value])))
    (t/is (= 10 (get-in global [:statistics :median :value])))
    (t/is (= 6 (get-in global [:counts :invocations])))
    (t/is (= 8 (get-in global [:counts :raw-values])))
    (t/is (str/includes? (format/format-run (summary/summarize run)) "invocations=4 raw-values=6 n=2"))))

(t/deftest phase-eligibility-keeps-preparation-after-reset-failure
  (let [run (-> (new-run 1 3 :reuse)
                (result/record-preparation (preparation :prep [(event :test/setup [8])]))
                (add-attempt :prep true :completed [(block :main :prep []) (block :reset :prep [])])
                (add-attempt :prep false :completed
                             [(block :main :prep [(event :test/pan [2])])
                              (block :reset :prep [(event :test/restore [3])])])
                (add-attempt :prep false :failed
                             [(block :main :prep [(event :test/pan [100])])
                              (assoc (block :reset :prep [(event :test/restore [200])]) :outcome :failed)])
                (result/finish-run "end" {:reason :failed})
                result/encode result/decode)
        report (first (:cases (summary/summarize run)))
        prep (get-in report [:metrics [:preparation :test/setup :command/elapsed]])]
    (t/is (= [0 1 2] (mapv :attempt-id (:attempts run))))
    (t/is (= {:warmups 0 :repetitions 1} (:remaining report)))
    (t/is (= 8 (get-in prep [:statistics :median :value])))
    (t/is (= 1 (get-in prep [:statistics :count])))
    (t/is (= :insufficient-observations (get-in prep [:statistics :median-ci :reason])))
    (t/is (= 0 (get-in prep [:counts :warmup])))
    (t/is (= [2] (:values (summary/observations run :saved/pan main-pan))))
    (t/is (= [3] (:values (summary/observations run :saved/pan [:reset :test/restore :command/elapsed]))))
    (t/is (= {:executions 3 :completed 3 :failed 0 :warmup 1} (get-in report [:phases :main])))
    (t/is (= {:executions 3 :completed 2 :failed 1 :warmup 1} (get-in report [:phases :reset])))))

(t/deftest fresh-preparations-follow-their-own-slot-eligibility
  (let [run (-> (new-run 1 2 :fresh)
                (result/record-preparation (assoc (preparation :warm [(event :test/setup [900])]) :warmup? true))
                (add-attempt :warm true :completed [(block :main :warm [])])
                (result/record-preparation (preparation :first [(event :test/setup [5])]))
                (add-attempt :first false :completed [(block :main :first [])])
                (result/record-preparation (preparation :second [(event :test/setup [7])]))
                (add-attempt :second false :failed [(assoc (block :main :second []) :outcome :failed)]))
        obs (summary/observations run :saved/pan [:preparation :test/setup :command/elapsed])]
    (t/is (= [5 7] (:values obs)))
    (t/is (= 1 (get-in obs [:counts :warmup])))
    (t/is (= 2 (get-in obs [:counts :valid])))))

(t/deftest fresh-warmup-preparation-is-excluded-before-main-delivery
  (let [run (-> (new-run 1 1 :fresh)
                (result/record-preparation
                 (assoc (preparation :prep [(event :test/setup [9])]) :warmup? true))
                result/encode result/decode)
        report (get-in (summary/summarize run)
                       [:cases 0 :metrics [:preparation :test/setup :command/elapsed]])]
    (t/is (= 1 (get-in report [:counts :warmup])))
    (t/is (= :no-valid-observations (get-in report [:statistics :median :reason])))))

(t/deftest failed-preparation-excludes-a-completed-callback-from-observations
  (let [run (-> (new-run 0 1 :reuse)
                (result/record-preparation
                 (assoc (preparation :prep [(event :test/setup [9])])
                        :failure {:phase "preparation" :message "initial Full failed"})))
        report (first (:cases (summary/summarize run)))
        metric (get-in report [:metrics [:preparation :test/setup :command/elapsed]])]
    (t/is (= {:executions 1 :completed 1 :failed 0 :warmup 0} (get-in report [:phases :preparation])))
    (t/is (= 1 (get-in metric [:counts :failed])))
    (t/is (= :no-valid-observations (get-in metric [:statistics :median :reason])))))

(t/deftest invalid-members-propagate-only-to-dependent-quantities
  (let [bad (assoc-in (event :test/pan [0]) [:metrics :renderer/call-elapsed] [1 js/NaN])
        good (assoc-in (event :test/zoom [0]) [:metrics :renderer/call-elapsed] [2])
        run (-> (new-run 0 1 :reuse)
                (result/record-preparation (preparation :prep []))
                (add-attempt :prep false :completed [(block :main :prep [bad good])]))
        report (first (:cases (summary/summarize run)))]
    (t/is (= [0] (:values (summary/observations run :saved/pan main-global))))
    (t/is (= [2] (:values (summary/observations run :saved/pan [:main :test/zoom :renderer/call-elapsed]))))
    (doseq [scope [:test/pan :global]]
      (let [metric (get-in report [:metrics [:main scope :renderer/call-elapsed]])]
        (t/is (= 1 (get-in metric [:counts :invalid])))
        (t/is (= :non-finite (get-in metric [:diagnostics 0 :reason])))
        (t/is (= :no-valid-observations (get-in metric [:statistics :median :reason])))))))

(t/deftest raw-special-values-and-partial-failures-survive-transit
  (let [values [0 -1 js/NaN js/Infinity js/-Infinity]
        initial (-> (new-run 0 6 :reuse)
                    (result/record-preparation (preparation :prep [])))
        run (-> (reduce (fn [run value]
                          (add-attempt run :prep false :completed
                                       [(block :main :prep [(event :test/pan [value])])]))
                        initial values)
                (result/record-attempt
                 (measurement/attempt :saved/pan :prep false
                                      {:status "failed" :phase "main" :message "render failed"
                                       :cause [{:message "WASM trap"}]
                                       :partial {:clock js/NaN}
                                       :phases [(assoc (block :main :prep [(event :test/pan [99])])
                                                       :outcome :failed)]}))
                (result/finish-run "end" {:reason :failed})
                result/encode result/decode)
        raw (mapv #(get-in % [:phases 0 :events 0 :metrics :command/elapsed 0]) (:attempts run))
        obs (summary/observations run :saved/pan main-pan)]
    (t/is (= [0 -1] (subvec raw 0 2)))
    (t/is (js/Number.isNaN (nth raw 2)))
    (t/is (= [js/Infinity js/-Infinity] (subvec raw 3 5)))
    (t/is (= [{:message "WASM trap"}] (get-in run [:attempts 5 :failure :cause])))
    (t/is (js/Number.isNaN (get-in run [:attempts 5 :partial :clock])))
    (t/is (= 99 (nth raw 5)))
    (t/is (= [0] (:values obs)))
    (t/is (= 1 (get-in obs [:counts :failed])))
    (t/is (= 4 (get-in obs [:counts :invalid])))))

(t/deftest empty-blocks-support-preparation-only-and-reset-only-cases
  (doseq [phase [:preparation :reset]]
    (let [run (-> (new-run 0 1 :reuse)
                  (result/record-preparation
                   (preparation :prep (if (= :preparation phase) [(event :test/work [2])] [])))
                  (add-attempt :prep false :completed
                               [(block :main :prep [])
                                (block :reset :prep (if (= :reset phase) [(event :test/work [2])] []))])
                  (result/record-case-outcome :saved/pan {:outcome :completed})
                  (result/finish-run "end" {:reason :completed})
                  result/encode result/decode)]
      (t/is (= [2] (:values (summary/observations run :saved/pan [phase :global :command/elapsed]))))))
  (let [empty-run (-> (new-run 0 1 :reuse)
                      (result/record-preparation (preparation :prep []))
                      (add-attempt :prep false :completed [(block :main :prep [])]))]
    (t/is (= ::result/invalid-record
             (error-type #(result/finish-run empty-run "end" {:reason :completed})))))
  (let [failed (-> (new-run 0 1 :reuse)
                   (result/record-preparation {:preparation-id :prep :case-id :saved/pan :warmup? false
                                               :diagnostics {} :phases []
                                               :failure {:phase "preparation" :message "setup failed"}})
                   (result/finish-run "end" {:reason :preparation-error}))]
    (t/is (str/includes? (format/format-run (summary/summarize (result/decode (result/encode failed))))
                         "setup failed"))))

(t/deftest comparison-reports-preparation-failures-once-per-source
  (let [failure {:phase "preparation" :message "setup failed"}
        run     (-> (new-run 0 2 :fresh)
                    (result/record-preparation (preparation :ready []))
                    (add-attempt :ready false :completed [(block :main :ready [(event :test/work [2])])])
                    (result/record-preparation {:preparation-id :failed :case-id :saved/pan :warmup? false
                                                :diagnostics {} :phases [] :failure failure})
                    (result/record-attempt (measurement/attempt :saved/pan :failed false
                                                                {:status "failed" :phase "preparation"
                                                                 :message "setup failed" :phases []}))
                    (result/record-case-outcome :saved/pan {:outcome :failed :failure failure})
                    (result/finish-run "end" {:reason :failed :failure failure})
                    result/encode result/decode)
        compared (-> (compare/compare-runs run (assoc run :run-id "candidate")
                                           {:allow-all-differences true})
                     result/encode result/decode)
        text    (format/format-comparison compared)]
    (t/is (= 2 (count (re-seq #"setup failed" text))))
    (t/is (str/includes? text "median change"))
    (t/is (= 2 (count (re-seq #"Preparation :failed" text))))))

(t/deftest offline-comparison-uses-one-analysis-and-saved-quantity-units
  (let [base (-> (sample-run [1 2 3]) result/encode result/decode)
        candidate (-> (sample-run [3 4 5])
                      (assoc-in [:analysis :seed] 99)
                      (assoc-in [:quantities :command/elapsed :description] "Reworded description")
                      result/encode result/decode)
        comparison (-> (compare/compare-runs base candidate {:analysis {:draws 20}})
                       result/encode result/decode)]
    (t/is (= 2 (get-in comparison [:pairs 0 :metrics main-pan :absolute :value])))
    (t/is (= 20 (get-in comparison [:analysis :draws])))
    (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
    (t/is (str/includes? (format/format-comparison comparison) "median change 2"))
    (let [changed (assoc-in candidate [:quantities :command/elapsed :unit] :seconds)]
      (t/is (= ::compare/incompatible (error-type #(compare/compare-runs base changed))))
      (t/is (= 2 (get-in (compare/compare-runs base changed {:allow-all-differences true})
                         [:pairs 0 :metrics main-pan :absolute :value]))))))

(t/deftest unknown-and-malformed-quantities-produce-diagnostics
  (doseq [[description reason] [[nil :unknown-quantity] [{:unit 2} :malformed-quantity]]]
    (let [run (cond-> (sample-run [1])
                (nil? description) (update :quantities dissoc :command/elapsed)
                (some? description) (assoc-in [:quantities :command/elapsed] description))
          report (get-in (summary/summarize (result/decode (result/encode run)))
                         [:cases 0 :metrics main-pan])]
      (t/is (= reason (get-in report [:diagnostics 0 :reason])))
      (t/is (= :no-valid-observations (get-in report [:statistics :median :reason]))))))

(defn- feature-run
  [features selection]
  (-> (sample-run [1])
      (assoc-in [:metadata :build :functional :features] features)
      (assoc-in [:metadata :build :provenance :feature-selection] selection)))

(t/deftest automatic-feature-permission-checks-effective-and-requested-evidence
  (let [baseline (feature-run ["base" "old"] {:default-features? false :features ["base"]})
        candidate (feature-run ["base" "toggle" "transitive"]
                               {:default-features? false :features ["base" "toggle"]})
        expected {:feature "toggle" :baseline-features ["base" "old"]
                  :candidate-features ["base" "toggle" "transitive"]
                  :added ["toggle" "transitive"] :removed ["old"]}
        edge (assoc expected :from "0" :to "1")
        report (compare/compare-runs baseline candidate {:expected-feature-change expected :edge edge})]
    (t/is (= :feature-toggle (get-in report [:pairs 0 :mismatches 0 :rule])))
    (t/is (= report (result/decode (result/encode report))))
    (doseq [invalid [nil (assoc expected :feature "base") (assoc expected :feature :toggle)
                     (assoc expected :baseline-features ["base"])
                     (assoc expected :candidate-features ["base" "toggle"])
                     (assoc expected :added ["toggle"]) (assoc expected :removed [])
                     (assoc expected :added #{"toggle" "transitive"})
                     (dissoc expected :baseline-features)]]
      (t/is (= ::compare/incompatible
               (error-type #(compare/compare-runs baseline candidate {:expected-feature-change invalid})))))
    (doseq [selection [nil {} {:default-features? true :features ["base" "toggle"]}
                       {:default-features? false :features ["toggle"]}
                       {:default-features? false :features #{"base" "toggle"}}]]
      (t/is (= ::compare/incompatible
               (error-type #(compare/compare-runs
                             baseline (assoc-in candidate [:metadata :build :provenance :feature-selection] selection)
                             {:expected-feature-change expected})))))
    (doseq [bad-edge [(dissoc edge :removed) (assoc edge :added []) (assoc edge :feature :toggle)]]
      (t/is (= ::compare/invalid-edge
               (error-type #(compare/compare-runs baseline candidate {:edge bad-edge})))))
    (t/is (= ::compare/invalid-edge
             (error-type #(compare/compare-runs baseline candidate
                                                {:edge edge :expected-feature-change (assoc expected :added [])}))))))

(t/deftest missing-nil-and-empty-effective-features-keep-distinct-meaning
  (doseq [before [::missing nil []] after [::missing nil []]]
    (let [with-features (fn [features]
                          (cond-> (sample-run [1])
                            (= ::missing features) (update-in [:metadata :build :functional] dissoc :features)
                            (not= ::missing features) (assoc-in [:metadata :build :functional :features] features)))
          baseline (-> (with-features before) result/encode result/decode)
          candidate (-> (with-features after) result/encode result/decode)
          compared (compare/compare-runs baseline candidate {:allow-build-differences true})]
      (t/is (= (if (= before after) [] [:effective-features])
               (mapv :field (get-in compared [:pairs 0 :mismatches])))))))

(t/deftest comparison-boundaries-validate-permissions-rules-and-intervals
  (let [base (sample-run [1])
        candidate (assoc-in (sample-run [2]) [:metadata :build :functional :mode] :debug)
        compared (compare/compare-runs base candidate {:allow-build-differences true})
        path [:pairs 0 :metrics main-pan]]
    (doseq [bad [(dissoc compared :permissions)
                 (assoc-in compared [:permissions :allow-build-differences] :yes)
                 (update compared :permissions dissoc :allow-scene-differences)
                 (assoc-in compared [:pairs 0 :mismatches 0 :rule] :other)
                 (assoc-in compared [:pairs 0 :mismatches 0 :waived?] false)
                 (update-in compared [:pairs 0 :mismatches 0] dissoc :rule)
                 (assoc-in compared (conj path :absolute :value) js/NaN)
                 (assoc-in compared (conj path :absolute-ci) {:lower 2 :upper 1})
                 (assoc-in compared [:pairs 0 :baseline :metrics main-pan :counts :valid] -1)]]
      (t/is (= ::result/invalid-record (error-type #(result/decode (transit/encode-str bad))))))))

(t/deftest scene-permission-requires-complete-consistent-preparation-evidence
  (let [base (sample-run [1])
        changed (result/record-preparation base
                                           (assoc-in (preparation :other []) [:fingerprint :digest]
                                                     (apply str (repeat 64 "b"))))
        report (compare/compare-runs base changed {:allow-all-differences true})]
    (t/is (= ::compare/incompatible
             (error-type #(compare/compare-runs base changed {:allow-scene-differences true}))))
    (t/is (some #(= [:fingerprint :incomplete-or-varying-evidence] (:field %))
                (get-in report [:pairs 0 :mismatches])))))

(t/deftest matching-missing-preparation-facts-produce-incomplete-evidence
  (let [run (-> (new-run 0 1 :reuse)
                (result/record-preparation
                 (-> (preparation :prep [(event :test/setup [9])])
                     (dissoc :fingerprint :effective-graphics)
                     (assoc :failure {:phase "preparation" :message "setup failed"}))))]
    (doseq [options [{} {:allow-scene-differences true}]]
      (t/is (= ::compare/incompatible (error-type #(compare/compare-runs run run options)))))
    (let [report (compare/compare-runs run run {:allow-all-differences true})]
      (t/is (= #{[:fingerprint :incomplete-or-varying-evidence]
                 [:effective-graphics :incomplete-or-varying-evidence]}
               (set (map :field (get-in report [:pairs 0 :mismatches]))))))))

(t/deftest saved-boundaries-refuse-corrupt-identities-and-phase-history
  (let [run (sample-run [1])]
    (doseq [bad [(assoc-in run [:attempts 0 :attempt-id] 3)
                 (assoc-in run [:attempts 0 :phases] [])
                 (assoc-in run [:preparations 0 :phases] [])
                 (assoc-in run [:preparations 0 :failure] {:phase "preparation" :message "setup failed"})
                 (update-in run [:attempts 0] assoc :outcome :failed
                            :phases [(assoc (block :main :prep []) :outcome :failed)
                                     (block :reset :prep [])])
                 (assoc-in run [:attempts 0 :preparation-id] :unknown)
                 (assoc-in run [:attempts 0 :phases 0 :preparation-id] :unknown)
                 (assoc-in run [:attempts 0 :phases 0 :events 0 :metrics :command/elapsed] [])
                 (assoc-in run [:attempts 0 :phases 0 :events 0 :metrics :command/elapsed] [1 2])
                 (assoc-in run [:remaining :saved/pan :repetitions] 2)]]
      (t/is (= ::result/invalid-record
               (error-type #(result/decode (transit/encode-str bad))))))))

(t/deftest saved-failures-reject-malformed-diagnostics
  (let [diagnostic {:phase "main" :message "renderer failed"}
        failed-preparation (-> (new-run 0 1 :reuse)
                               (result/record-preparation (assoc (preparation :prep []) :failure diagnostic))
                               (result/finish-run "end" {:reason :failed})
                               result/encode result/decode)
        failed-attempt (-> (new-run 0 1 :reuse)
                           (result/record-preparation (preparation :prep []))
                           (add-attempt :prep false :failed [(assoc (block :main :prep []) :outcome :failed)])
                           (result/record-case-outcome :saved/pan {:outcome :failed :failure diagnostic})
                           (result/finish-run "end" {:reason :failed :failure diagnostic})
                           (assoc-in [:metadata :diagnostics :failures] [diagnostic])
                           result/encode result/decode)]
    (doseq [[run path] [[failed-preparation [:preparations 0 :failure]]
                        [failed-attempt [:attempts 0 :failure]]
                        [failed-attempt [:case-outcomes :saved/pan :failure]]
                        [failed-attempt [:termination :failure]]
                        [failed-attempt [:metadata :diagnostics :failures 0]]]
            invalid [{:phase 42 :message "bad location"}
                     {:phase "main" :message nil}
                     {:phase "main" :message "bad cause" :cause [42]}]]
      (t/is (= ::result/invalid-record
               (error-type #(result/decode (transit/encode-str (assoc-in run path invalid)))))))))

(t/deftest case-outcomes-validate-completion-and-close-the-recording-boundary
  (let [run (sample-run [1])
        completed (result/record-case-outcome run :saved/pan {:outcome :completed})
        ended (result/finish-run completed "end" {:reason :completed})]
    (t/is (= ended (result/decode (result/encode ended))))
    (doseq [bad [(assoc ended :case-outcomes {})
                 (-> run
                     (assoc-in [:plan :repetitions] 2)
                     (assoc-in [:remaining :saved/pan :repetitions] 1)
                     (assoc-in [:case-outcomes :saved/pan] {:outcome :completed}))
                 (assoc-in ended [:case-outcomes :unknown] {:outcome :completed})]]
      (t/is (= ::result/invalid-record (error-type #(result/decode (transit/encode-str bad))))))
    (t/is (= ::result/invalid-record
             (error-type #(result/record-preparation completed (preparation :other [])))))
    (let [stopped (-> run
                      (assoc-in [:plan :repetitions] 2)
                      (assoc-in [:remaining :saved/pan :repetitions] 1)
                      (result/record-case-outcome :saved/pan {:outcome :failed}))]
      (t/is (thrown-with-msg? js/Error #"case already terminated"
                              (add-attempt stopped :prep false :completed [(block :main :prep [])]))))))
