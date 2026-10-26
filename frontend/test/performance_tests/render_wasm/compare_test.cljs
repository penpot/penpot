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
   :scene-version 1
   :scene-description "comparison scene"
   :scene-seed 7
   :params {:count 10}
   :view {:scale 1 :x 0 :y 0}
   :context :fresh
   :completion :render-full
   :batch-size 1
   :preparation {:version 1}})

(defn- make-run
  [run-id case-id value & {:keys [environment functional fingerprint scene]
                           :or {environment {:node "v24" :browser "Fake"}
                                functional {:mode :release}
                                fingerprint (apply str (repeat 64 "a"))
                                scene :case}}]
  (let [preparation-id (str run-id "-preparation")
        run (result/create-run
             {:run-id run-id
              :started-at "2026-10-26T00:00:00.000Z"
              :plan {:cases [(case-definition case-id scene)]
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
              :fingerprint {:encoding-version 1 :algorithm :sha-256
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

(t/deftest compares-saved-file-paths-from-the-caller-cwd
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
        (t/is (= 2 (:schema-version comparison)))
        (t/is (str/includes? text "Baseline"))
        (t/is (str/includes? text "Candidate"))
        (t/is (str/includes? text "case/load"))
        (t/is (str/includes? text "median change")))
      (finally
        (.chdir js/process previous)))))

(t/deftest compare-cases-selects-one-explicit-baseline-candidate-pair
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :a/pan 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :b/pan 12 :scene :other))
        {:keys [text]} (compare/execute baseline candidate
                                        {:compare-cases "a/pan,b/pan"})]
    (t/is (str/includes? text "Pair a/pan,b/pan"))))

(t/deftest force-bypasses-differences-and-displays-them
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :case/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :case/load 12
                                       :environment {:node "v25" :browser "Fake"}))]
    (t/is (= ::report-compare/incompatible
             (:type (ex-data (failure #(compare/execute baseline candidate {}))))))
    (let [{:keys [text]} (compare/execute baseline candidate {:force true})]
      (t/is (str/starts-with? text "FORCED COMPARISON"))
      (t/is (str/includes? text "MISMATCHES")))))

(t/deftest invalid-selectors-and-unreadable-records-fail-at-the-boundary
  (let [baseline  (write-run (path/join @temporary-root "baseline.transit")
                             (make-run "baseline" :case/load 10))
        candidate (write-run (path/join @temporary-root "candidate.transit")
                             (make-run "candidate" :case/load 12))]
    (doseq [selector ["case/load" "case/load,other/load,third/load" ""]]
      (t/is (= ::report-compare/invalid-selector
               (:type (ex-data (failure #(compare/execute baseline candidate
                                                          {:compare-cases selector})))))))
    (fs/writeFileSync (path/join @temporary-root "broken.transit") "not transit")
    (t/is (thrown? js/Error (compare/execute baseline
                                             (path/join @temporary-root "broken.transit")
                                             {})))
    (t/is (thrown? js/Error (compare/execute baseline
                                             (path/join @temporary-root "missing.transit")
                                             {})))))
