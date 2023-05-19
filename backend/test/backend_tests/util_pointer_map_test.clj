;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.util-pointer-map-test
  (:require
   [app.common.fressian :as fres]
   [app.common.spec :as us]
   [app.common.transit :as transit]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as props]))

(t/deftest basic-operations
  (t/testing "assoc"
    (let [obj (-> (pmap/create) (assoc :a 1))]
      (t/is (contains? obj :a))
      (t/is (not (contains? obj :b)))
      (t/is (= 1 (count obj)))))

  (t/testing "dissoc"
    (let [obj (pmap/wrap {:a 1 :b 2})]
      (t/is (contains? obj :a))
      (t/is (contains? obj :b))
      (let [obj (dissoc obj :a)]
        (t/is (not (contains? obj :a)))
        (t/is (contains? obj :b)))))

  (t/testing "seq"
    (let [obj (pmap/wrap {:a 1 :b 2})
          s1  (first obj)]
      (t/is (= (key s1) :a))
      (t/is (= (val s1) 1))))

  (t/testing "cons & count"
    (let [obj (into (pmap/create) [[:a 1]])]
      (t/is (contains? obj :a))
      (t/is (= 1 (count obj)))
      (t/is (pmap/pointer-map? obj))))

  (t/testing "wrap"
    (let [obj1 (pmap/wrap {})
          tmp  (pmap/create)
          obj2 (pmap/wrap tmp)]
      (t/is (pmap/pointer-map? obj1))
      (t/is (pmap/pointer-map? obj2))
      (t/is (identical? tmp obj2))
      (t/is (= 0 (count obj1)))
      (t/is (= 0 (count obj2)))))
  )


(t/deftest internal-tracking
  (t/testing "simple tracking"
    (binding [pmap/*tracked* (atom {})]
      (let [obj (pmap/create)]
        (t/is (pmap/modified? obj))
        (t/is (uuid? (pmap/get-id obj)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj)))
        (t/is (= 1 (count @pmap/*tracked*))))))

  (t/testing "tracking modifying modified"
    (binding [pmap/*tracked* (atom {})]
      (let [obj1 (pmap/create)
            obj2 (assoc obj1 :a 1)]
        (t/is (pmap/modified? obj1))
        (t/is (not= obj1 obj2))
        (t/is (= 0 (count obj1)))
        (t/is (= 1 (count obj2)))
        (t/is (uuid? (pmap/get-id obj1)))
        (t/is (uuid? (pmap/get-id obj2)))
        (t/is (= (pmap/get-id obj2)
                 (pmap/get-id obj1)))
        (t/is (= (hash (pmap/get-id obj2))
                 (hash (pmap/get-id obj1))))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj2)))
        (t/is (= 1 (count @pmap/*tracked*))))))

  (t/testing "tracking modifying not modified"
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (constantly {})]
      (let [obj1 (pmap/create uuid/zero {})
            obj2 (assoc obj1 :a 1)]
        (t/is (pmap/modified? obj2))
        (t/is (not (pmap/modified? obj1)))
        (t/is (not= obj1 obj2))
        (t/is (= 0 (count obj1)))
        (t/is (= 1 (count obj2)))
        (t/is (uuid? (pmap/get-id obj1)))
        (t/is (uuid? (pmap/get-id obj2)))
        (t/is (not= (pmap/get-id obj2)
                    (pmap/get-id obj1)))
        (t/is (not= (hash (pmap/get-id obj2))
                    (hash (pmap/get-id obj1))))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj1)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj2)))
        (t/is (= 2 (count @pmap/*tracked*))))))

  (t/testing "loading"
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (constantly {:a 1})]
      (let [obj1 (pmap/create uuid/zero {})]
        (t/is (not (pmap/modified? obj1)))
        (t/is (= 1 (count obj1)))
        (t/is (= uuid/zero (pmap/get-id obj1)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj1)))
        (t/is (= 1 (count @pmap/*tracked*)))
        (t/is (contains? obj1 :a))
        (t/is (not (contains? obj1 :b)))
        (t/is (= 1 (get obj1 :a)))
        (t/is (= nil (get obj1 :b)))
        (t/is (= ::empty (get obj1 :b ::empty))))))

  )

