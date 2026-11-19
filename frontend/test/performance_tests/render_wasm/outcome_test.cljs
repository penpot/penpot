;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.outcome-test
  (:require
   [benches.render-wasm.node.outcome :as outcome]
   [cljs.test :as t :include-macros true]))

(t/deftest failed-reports-the-cause-with-exit-code-1-and-its-log-path
  (let [cause  (ex-info "boom" {:log-path "build.log"})
        result (outcome/failed cause {:home :h})]
    (t/is (= [cause] (:errors result)))
    (t/is (= 1 (:exit-code result)))
    (t/is (= "build.log" (:log-path result)))
    (t/is (= :h (:home result)))))

(t/deftest failed-prefers-the-log-path-given-in-data
  (let [result (outcome/failed (ex-info "boom" {:log-path "build.log"}) {:log-path "run.log"})]
    (t/is (= "run.log" (:log-path result)))))

(t/deftest add-error-appends-and-keeps-interruption-exit-codes
  (doseq [[before after] [[0 1] [1 1] [130 130] [143 143]]]
    (let [first-cause  (js/Error. "first")
          second-cause (js/Error. "second")
          result       (-> {:errors [first-cause] :exit-code before}
                           (outcome/add-error second-cause))]
      (t/is (= [first-cause second-cause] (:errors result)))
      (t/is (= after (:exit-code result))))))

(t/deftest publish-returns-the-outcome-when-printing-succeeds
  (let [printed (atom nil)
        result  (outcome/publish {:exit-code 0} #(reset! printed :done))]
    (t/is (= :done @printed))
    (t/is (= {:exit-code 0} result))))

(t/deftest publish-adds-a-failed-print-to-the-outcome
  (let [cause  (js/Error. "stream closed")
        result (outcome/publish {:exit-code 0} #(throw cause))]
    (t/is (= [cause] (:errors result)))
    (t/is (= 1 (:exit-code result)))))

(t/deftest interruption-code-maps-signal-names
  (t/is (= 143 (outcome/interruption-code "SIGTERM")))
  (t/is (= 130 (outcome/interruption-code "SIGINT"))))
