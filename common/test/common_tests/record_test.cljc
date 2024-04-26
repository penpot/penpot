;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.record-test
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as cr]
   [clojure.test :as t]))

(cr/defrecord Sample [a b])

(t/deftest operations
  (let [o (pos->Sample 1 2)]

    (t/testing "get"
      (t/is (= 1 (:a o)))
      (t/is (= 1 (get o :a)))
      (t/is (= nil (get o :c)))
      (t/is (= :foo (get o :c :foo))))

    (t/testing "known assoc"
      (let [o (assoc o :a 100)]
        (t/is (= 100 (:a o)))))

    (t/testing "unknown assoc"
      (let [o (assoc o :c 176)]
        (t/is (= 1 (:a o)))
        (t/is (= 2 (:b o)))
        (t/is (= 176 (:c o)))))

    (t/testing "contains"
      (let [o (assoc o :c 176)]
        (t/is (contains? o :a))
        (t/is (contains? o :b))
        (t/is (contains? o :c))
        (t/is (not (contains? o :d)))))

    #?(:cljs
       (t/testing "transients"
         (let [o (assoc o :c 123)
               u (cr/clone o)]
           (cr/assoc! u :a 10)
           (cr/assoc! u :b 20)
           (cr/assoc! u :c 124)

           (t/is (= 10  (dm/get-prop u :a)))
           (t/is (= 20  (dm/get-prop u :b)))
           (t/is (= 124 (:c u)))
           (t/is (not= u o)))))))

