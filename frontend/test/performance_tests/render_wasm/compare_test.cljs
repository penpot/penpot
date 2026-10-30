;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.compare-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.node.compare :as compare]
   [benches.render-wasm.report.compare :as report-compare]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private temporary-root (atom nil))

(t/use-fixtures :each
  {:before #(reset! temporary-root
                    (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-compare-")))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

(defn- metric
  [value]
  (result/metric {:value value
                  :unit :ms
                  :semantics :elapsed
                  :basis {:kind :attempt-total}
                  :zero-policy :positive}))

(defn- case-definition
  [case-id scene]
  {:id case-id
   :scene scene
   :scene-description "comparison scene"
   :scene-seed 7
   :params {:count 10}
   :view {:scale 1 :x 0 :y 0}
   :context :fresh
   :completion :render-full
   :batch-size 1
   :preparation {}})

(defn- make-run
  [run-id case-id value & {:keys [environment functional fingerprint scene case-settings]
                           :or {environment {:node "v24" :browser "Fake"}
                                functional {:mode :release}
                                fingerprint (apply str (repeat 64 "a"))
                                scene :case}}]
  (let [preparation-id (str run-id "-preparation")
        run (result/create-run
             {:run-id run-id
              :started-at "2026-10-26T00:00:00.000Z"
              :plan {:cases [(merge (case-definition case-id scene) case-settings)]
                     :warmups 0
                     :repetitions 1
                     :timeout-policy {:attempt-ms 30000}}
              :metadata {:git {:sha run-id :dirty false}
                         :build {:functional functional}
                         :environment environment
                         :scored? true}})
        run (result/record-preparation
             run
             {:preparation-id preparation-id
              :case-id case-id
              :fingerprint {:algorithm :sha-256
                            :digest fingerprint}
              :effective-graphics {:webgl2 true}
              :diagnostics {}})
        run (result/record-attempt
             run
             {:case-id case-id
              :preparation-id preparation-id
              :warmup? false
              :outcome :completed
              :metrics {:elapsed (metric value)}
              :trace {}})]
    (result/finish-run run "2026-10-26T00:01:00.000Z" {:reason :completed})))

(defn- write-run
  [file run]
  (fs/writeFileSync file (result/encode run) "utf8")
  file)

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch :default cause cause)))

(t/deftest compares-saved-records-from-the-caller-cwd
  (write-run (path/join @temporary-root "baseline.transit")
             (make-run "baseline" :case/load 10))
  (write-run (path/join @temporary-root "candidate.transit")
             (make-run "candidate" :case/load 12))
  (let [previous (.cwd js/process)]
    (try
      (.chdir js/process @temporary-root)
      (let [{:keys [comparison text]} (compare/execute "baseline.transit"
                                                       "candidate.transit" {})]
        (t/is (= :comparison (:type comparison)))
        (t/is (= {:allow-build-differences false
                  :allow-scene-differences false
                  :allow-all-differences false}
                 (:permissions comparison)))
        (t/is (empty? (get-in comparison [:pairs 0 :mismatches])))
        (t/is (= 2 (get-in comparison [:pairs 0 :metrics :elapsed :absolute :value])))
        (t/is (str/includes? text "Baseline"))
        (t/is (str/includes? text "Candidate"))
        (t/is (str/includes? text "case/load"))
        (t/is (str/includes? text "median change")))
      (finally
        (.chdir js/process previous)))))

(t/deftest cases-selects-one-explicit-baseline-candidate-pair
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :a/pan 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :b/pan 12))
        {:keys [text]} (compare/execute baseline candidate
                                        {:cases "a/pan,b/pan"})]
    (t/is (str/includes? text "Pair a/pan,b/pan"))))

(t/deftest cases-requires-scene-permission-for-different-scenes
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :a/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :b/load 12 :scene :other
                                       :fingerprint (apply str (repeat 64 "b"))))
        selector  {:cases "a/load,b/load"}]
    (t/is (= ::report-compare/incompatible
             (:type (ex-data (failure #(compare/execute baseline candidate selector))))))
    (t/is (= ::report-compare/incompatible
             (:type (ex-data (failure #(compare/execute baseline candidate
                                                        (assoc selector :allow-build-differences true)))))))
    (let [{:keys [comparison text]}
          (compare/execute baseline candidate (assoc selector :allow-scene-differences true))]
      (t/is (= {:allow-build-differences false
                :allow-scene-differences true
                :allow-all-differences false}
               (:permissions comparison)))
      (t/is (= #{:scene :fingerprint}
               (set (map :field (get-in comparison [:pairs 0 :mismatches])))))
      (t/is (every? #(and (:waived? %) (= :scene (:rule %)))
                    (get-in comparison [:pairs 0 :mismatches])))
      (t/is (str/includes? text "ACKNOWLEDGED DIFFERENCE"))
      (t/is (str/includes? text "Pair a/load,b/load")))))

(t/deftest build-permission-allows-build-differences-only
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :case/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :case/load 12
                                       :functional {:mode :debug :features ["fixture"]}))]
    (doseq [options [{} {:allow-scene-differences true}]]
      (t/is (= ::report-compare/incompatible
               (:type (ex-data (failure #(compare/execute baseline candidate options)))))))
    (let [{:keys [comparison text]}
          (compare/execute baseline candidate {:allow-build-differences true})]
      (t/is (= {:allow-build-differences true
                :allow-scene-differences false
                :allow-all-differences false}
               (:permissions comparison)))
      (t/is (= #{:functional-build :effective-features}
               (set (map :field (get-in comparison [:pairs 0 :mismatches])))))
      (t/is (every? #(and (:waived? %) (= :build (:rule %)))
                    (get-in comparison [:pairs 0 :mismatches])))
      (t/is (str/includes? text ":rule :build")))))

(t/deftest build-and-scene-permissions-combine
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :case/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :case/load 12 :scene :other
                                       :functional {:mode :debug}))]
    (doseq [options [{:allow-build-differences true} {:allow-scene-differences true}]]
      (t/is (= ::report-compare/incompatible
               (:type (ex-data (failure #(compare/execute baseline candidate options)))))))
    (let [{:keys [comparison]}
          (compare/execute baseline candidate {:allow-build-differences true
                                               :allow-scene-differences true})]
      (t/is (= {:allow-build-differences true
                :allow-scene-differences true
                :allow-all-differences false}
               (:permissions comparison)))
      (t/is (= {:scene :scene :functional-build :build}
               (into {} (map (juxt :field :rule))
                     (get-in comparison [:pairs 0 :mismatches])))))))

(t/deftest scoped-permissions-keep-operation-and-runtime-checks-strict
  (let [baseline (write-run (path/join @temporary-root "baseline.transit")
                            (make-run "baseline" :case/load 10))]
    (doseq [[field settings]
            [[:execution {:case-settings {:operation {:id :pan :args {:distance 20}}}}]
             [:environment {:environment {:node "v25" :browser "Fake"}}]]]
      (let [candidate (write-run (path/join @temporary-root "candidate.transit")
                                 (apply make-run "candidate" :case/load 12 (mapcat identity settings)))
            cause     (failure #(compare/execute baseline candidate
                                                 {:allow-build-differences true
                                                  :allow-scene-differences true}))]
        (t/is (= ::report-compare/incompatible (:type (ex-data cause))))
        (t/is (= [field] (mapv :field (:mismatches (ex-data cause)))))
        (t/is (every? (comp not :waived?) (:mismatches (ex-data cause))))))))

(t/deftest all-permission-allows-runtime-differences-and-displays-them
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :case/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :case/load 12
                                       :environment {:node "v25" :browser "Fake"}))]
    (t/is (= ::report-compare/incompatible
             (:type (ex-data (failure #(compare/execute baseline candidate {}))))))
    (t/is (= ::report-compare/incompatible
             (:type (ex-data (failure #(compare/execute baseline candidate {:force true}))))))
    (let [{:keys [comparison text]}
          (compare/execute baseline candidate {:allow-all-differences true})]
      (t/is (= {:allow-build-differences false
                :allow-scene-differences false
                :allow-all-differences true}
               (:permissions comparison)))
      (t/is (= [{:field :environment
                 :baseline {:node "v24" :browser "Fake"}
                 :candidate {:node "v25" :browser "Fake"}
                 :waived? true :rule :all}]
               (get-in comparison [:pairs 0 :mismatches])))
      (t/is (str/starts-with? text "Permissions "))
      (t/is (str/includes? text "ACKNOWLEDGED DIFFERENCE"))
      (t/is (str/includes? text ":rule :all")))))

(t/deftest invalid-selectors-and-unreadable-records-fail-at-the-boundary
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :case/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :case/load 12))]
    (let [file   (path/join @temporary-root "comparison.transit")
          record (:comparison (compare/execute baseline candidate {}))]
      (fs/writeFileSync file (result/encode record))
      (t/is (thrown-with-msg? js/Error #"expected a saved run record"
                              (compare/execute baseline file {}))))
    (doseq [selector ["case/load" "case/load,other/load,third/load" ""]]
      (t/is (= ::report-compare/invalid-selector
               (:type (ex-data (failure #(compare/execute baseline candidate
                                                          {:cases selector})))))))
    (fs/writeFileSync (path/join @temporary-root "broken.transit") "not transit")
    (t/is (thrown? js/Error (compare/execute baseline
                                             (path/join @temporary-root "broken.transit")
                                             {})))
    (fs/writeFileSync (path/join @temporary-root "malformed.transit")
                      (transit/encode-str (dissoc (make-run "malformed" :case/load 12) :plan)))
    (doseq [options [{} {:allow-all-differences true}]]
      (t/is (= ::result/invalid-record
               (:type (ex-data (failure #(compare/execute baseline
                                                          (path/join @temporary-root "malformed.transit")
                                                          options)))))))
    (t/is (thrown? js/Error (compare/execute baseline
                                             (path/join @temporary-root "missing.transit")
                                             {})))))
