;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.merge-test
  (:require
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [cljs.test :as t :include-macros true]))

(defn- metric
  [value]
  {:command :fixture/work :metrics {:command/elapsed [value]}})

(defn- case-definition
  [case-id]
  {:id case-id
   :scene :case
   :scene-description "merge scene"
   :scene-seed 7
   :params {:count 10}
   :view {:scale 1 :x 0 :y 0}
   :context :fresh
   :preparation {}})

(defn- preparation
  [preparation-id case-id]
  {:preparation-id preparation-id
   :case-id case-id :warmup? false
   :fingerprint {:algorithm :sha-256
                 :digest (apply str (repeat 64 "a"))}
   :effective-graphics {:webgl2 true}
   :diagnostics {}
   :phases [{:phase :preparation :preparation-id preparation-id :outcome :completed :events []}]})

(defn- attempt
  [case-id preparation-id warmup?]
  {:case-id case-id
   :preparation-id preparation-id
   :warmup? warmup?
   :outcome :completed
   :phases [{:phase :main :preparation-id preparation-id
             :outcome :completed :events [(metric 1.0)]}]})

(defn- make-run
  [{:keys [run-id cases warmups repetitions git-sha build environment analysis
           timeout-ms started-at ended-at termination preparations]
    :or {git-sha "same-sha"
         build {:mode :release}
         environment {:node "v24"}
         timeout-ms 30000
         started-at "2026-10-26T00:00:00.000Z"
         ended-at "2026-10-26T00:01:00.000Z"
         termination {:reason :completed}}}]
  (let [run (result/create-run
             {:run-id run-id
              :started-at started-at
              :plan {:cases (mapv case-definition cases)
                     :warmups warmups
                     :repetitions repetitions
                     :timeout-policy {:case-ms timeout-ms}}
              :metadata {:git {:sha git-sha :dirty false}
                         :build {:functional build}
                         :environment environment
                         :scored? true}
              :analysis analysis})
        run (reduce
             (fn [run case-id]
               (let [options (get preparations case-id)
                     warmup-id (or (:warmup options)
                                   (str run-id "-" (name case-id)))
                     measured-id (or (:measured options) warmup-id)]
                 (-> run
                     (result/record-preparation (preparation warmup-id case-id))
                     (cond-> (not= warmup-id measured-id)
                       (result/record-preparation (preparation measured-id case-id)))
                     (as-> run
                           (reduce (fn [run _]
                                     (result/record-attempt
                                      run (attempt case-id warmup-id true)))
                                   run (range warmups))
                       (reduce (fn [run _]
                                 (result/record-attempt
                                  run (attempt case-id measured-id false)))
                               run (range repetitions))))))
             run cases)
        run (if (= :completed (:reason termination))
              (reduce #(result/record-case-outcome %1 %2 {:outcome :completed}) run cases)
              run)]
    (result/finish-run run ended-at termination)))

(t/deftest merges-measured-attempts-and-updates-the-plan
  (let [base (make-run {:run-id "base"
                        :cases [:case/load :case/pan]
                        :warmups 1
                        :repetitions 2
                        :started-at "2026-10-26T00:00:00.000Z"
                        :ended-at "2026-10-26T00:01:00.000Z"})
        additional (make-run {:run-id "additional"
                              :cases [:case/load :case/pan]
                              :warmups 1
                              :repetitions 3
                              :started-at "2026-10-26T00:02:00.000Z"
                              :ended-at "2026-10-26T00:03:00.000Z"})
        merged (result/merge-runs base additional {:run-id "merged"})]
    (t/is (= "merged" (:run-id merged)))
    (t/is (= 1 (get-in merged [:plan :warmups])))
    (t/is (= 5 (get-in merged [:plan :repetitions])))
    (t/is (= 12 (count (:attempts merged))))
    (t/is (= (range 12) (map :attempt-id (:attempts merged))))
    (t/is (= [true false false true false false false false false false false false]
             (map :warmup? (:attempts merged))))
    (t/is (every? #(every? zero? (vals %)) (vals (:remaining merged))))
    (t/is (= "2026-10-26T00:03:00.000Z" (:ended-at merged)))
    (t/is (= 2 (get-in merged [:metadata :diagnostics :merge
                               :additional-warmups-excluded])))
    (t/is (= merged (result/check-run merged)))
    (t/is (= (:attempts merged)
             (:attempts (result/decode (result/encode merged)))))))

(t/deftest excludes-additional-warmup-only-preparations
  (let [base (make-run {:run-id "base"
                        :cases [:case/load]
                        :warmups 1
                        :repetitions 1})
        additional (make-run {:run-id "additional"
                              :cases [:case/load]
                              :warmups 1
                              :repetitions 1
                              :preparations
                              {:case/load {:warmup "warm-prep"
                                           :measured "measured-prep"}}})
        merged (result/merge-runs base additional {:run-id "merged"})
        preparation-ids (map :preparation-id (:preparations merged))]
    (t/is (some #{"base-load"} preparation-ids))
    (t/is (some #{"measured-prep"} preparation-ids))
    (t/is (not (some #{"warm-prep"} preparation-ids)))
    (t/is (= 3 (count (:attempts merged))))
    (t/is (= "measured-prep" (:preparation-id (last (:attempts merged)))))))

(t/deftest rejects-incompatible-runs
  (let [base (make-run {:run-id "base"
                        :cases [:case/load]
                        :warmups 1
                        :repetitions 1})]
    (doseq [[label options message]
            [["case" {:cases [:case/pan]} #"different case definitions"]
             ["timeout" {:timeout-ms 1000} #"different timeout policy"]
             ["warmups" {:warmups 0} #"different warmup count"]
             ["analysis" {:analysis {:draws 1000}} #"different analysis settings"]
             ["git" {:git-sha "other"} #"different git provenance"]
             ["build" {:build {:mode :debug}} #"different build facts"]
             ["environment" {:environment {:node "v25"}}
              #"different runtime environment"]]]
      (t/testing label
        (let [additional (make-run (merge {:run-id "additional"
                                           :cases [:case/load]
                                           :warmups 1
                                           :repetitions 1}
                                          options))]
          (t/is (thrown-with-msg? js/Error message
                                  (result/merge-runs base additional
                                                     {:run-id "merged"}))))))))

(t/deftest rejects-non-completed-runs
  (let [completed (make-run {:run-id "base"
                             :cases [:case/load]
                             :warmups 1
                             :repetitions 1})
        interrupted (make-run {:run-id "additional"
                               :cases [:case/load]
                               :warmups 1
                               :repetitions 1
                               :termination {:reason :interrupted}})]
    (t/is (thrown-with-msg? js/Error #"only completed runs can be merged"
                            (result/merge-runs completed interrupted
                                               {:run-id "merged"})))
    (t/is (thrown-with-msg? js/Error #"only completed runs can be merged"
                            (result/merge-runs interrupted completed
                                               {:run-id "merged"})))))

(t/deftest rejects-duplicate-preparation-ids
  (let [base (make-run {:run-id "base"
                        :cases [:case/load]
                        :warmups 1
                        :repetitions 1
                        :preparations
                        {:case/load {:warmup "shared"
                                     :measured "shared"}}})
        additional (make-run {:run-id "additional"
                              :cases [:case/load]
                              :warmups 1
                              :repetitions 1
                              :preparations
                              {:case/load {:warmup "shared"
                                           :measured "shared"}}})]
    (t/is (thrown-with-msg? js/Error #"duplicate preparation ids"
                            (result/merge-runs base additional
                                               {:run-id "merged"})))))

(t/deftest quantity-prose-does-not-change-merge-compatibility
  (let [base (make-run {:run-id "base" :cases [:case/load] :warmups 0 :repetitions 1})
        additional (-> (make-run {:run-id "additional" :cases [:case/load] :warmups 0 :repetitions 1})
                       (assoc-in [:quantities :command/elapsed :description] "Elapsed command work."))
        merged (result/merge-runs base additional {:run-id "merged"})]
    (t/is (= 2 (get-in merged [:plan :repetitions])))
    (t/is (= merged (result/decode (result/encode merged))))
    (let [malformed (assoc-in additional [:quantities :command/elapsed :description] 42)
          report (summary/summarize malformed)]
      (t/is (= :malformed-quantity
               (get-in report [:cases 0 :metrics [:main :fixture/work :command/elapsed] :diagnostics 0 :reason])))
      (t/is (thrown-with-msg? js/Error #"malformed quantity descriptions"
                              (result/merge-runs base malformed {:run-id "invalid"}))))
    (t/is (thrown-with-msg? js/Error #"different quantity units"
                            (result/merge-runs base
                                               (assoc-in additional [:quantities :command/elapsed :unit] :seconds)
                                               {:run-id "invalid"})))))
