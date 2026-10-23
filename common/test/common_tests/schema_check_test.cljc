;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.schema-check-test
  (:require
   [app.common.schema.test :as smt]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.test.check.generators :as gen]))

(t/deftest check-preserves-size-and-seed
  (let [seen (atom [])
        property (smt/for [size (gen/sized gen/return)]
                   (swap! seen conj size)
                   true)
        run (fn []
              (reset! seen [])
              (smt/check! property :num 4 :max-size 1 :seed 712)
              @seen)]
    (t/is (= (run) (run)))
    (t/is (every? zero? @seen))))

(t/deftest failure-reports-the-shrunk-case
  (let [output (with-out-str
                 (smt/default-reporter-fn
                  {:type :shrunk :seed 712 :shrunk {:smallest [10]}}))]
    (t/is (str/includes? output "712"))
    (t/is (str/includes? output ":smallest [10]"))))

(t/deftest check-uses-the-environment-seed
  (let [seed (atom nil)
        expected #?(:clj (some-> (System/getenv "PENPOT_GEN_SEED") parse-long)
                    :cljs (some-> js/globalThis .-process .-env .-PENPOT_GEN_SEED js/Number))]
    (smt/check! (smt/for [value gen/nat] (<= 0 value))
                :num 1
                :reporter-fn #(when (= :complete (:type %)) (reset! seed (:seed %))))
    (when (some? expected)
      (t/is (= expected @seed)))))
