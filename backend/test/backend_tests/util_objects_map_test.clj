;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.util-objects-map-test
  (:require
   [backend-tests.helpers :as th]
   [app.common.spec :as us]
   [app.common.transit :as transit]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.util.fressian :as fres]
   [app.util.objects-map :as omap]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as props]))

(t/deftest basic-operations
  (t/testing "assoc"
    (let [id  (uuid/custom 0 1)
          id' (uuid/custom 0 2)
          obj (-> (omap/create) (assoc id {:foo 1}))]
      (t/is (not= id id'))
      (t/is (not (contains? obj id')))
      (t/is (contains? obj id))))

  (t/testing "dissoc"
    (let [id  (uuid/custom 0 1)
          obj (-> (omap/create) (assoc id {:foo 1}))]
      (t/is (contains? obj id))
      (let [obj (dissoc obj id)]
        (t/is (not (contains? obj id))))))

  (t/testing "seq"
    (let [id  (uuid/custom 0 1)
          obj (-> (omap/create) (assoc id 1))]
      (t/is (contains? obj id))
      (let [[entry] (seq obj)]
        (t/is (map-entry? entry))
        (t/is (= (key entry) id))
        (t/is (= (val entry) 1)))))

  (t/testing "cons & count"
    (let [obj (into (omap/create) [[uuid/zero 1]])]
      (t/is (contains? obj uuid/zero))
      (t/is (= 1 (count obj)))
      (t/is (omap/objects-map? obj))))

  (t/testing "wrap"
    (let [obj1 (omap/wrap {})
          tmp  (omap/create)
          obj2 (omap/wrap tmp)]
      (t/is (omap/objects-map? obj1))
      (t/is (omap/objects-map? obj2))
      (t/is (identical? tmp obj2))
      (t/is (= 0 (count obj1)))
      (t/is (= 0 (count obj2)))))

  (t/testing "error on non-uuid keys"
    (let [obj (omap/wrap {})]
      (t/is (thrown? IllegalArgumentException (assoc obj :foo "bar")))))

  )

(t/deftest internal-operation
  (t/testing "modified & compact"
    (let [id1 (uuid/custom 0 1)
          id2 (uuid/custom 0 2)
          obj (omap/wrap {id1 1 id2 2})]
      (t/is (= 2 (count obj)))
      (t/is (omap/modified? obj))
      (omap/compact! obj)
      (t/is (not (omap/modified? obj)))
      (t/is (bytes? (deref obj)))))

  (t/testing "low-level serialize/deserialize"
    (let [id1 (uuid/custom 0 1)
          id2 (uuid/custom 0 2)
          obj1 (omap/wrap {id1 1 id2 2})
          obj2 (omap/create (deref obj1))]
      (t/is (= (get obj1 id1) (get obj2 id1)))
      (t/is (= (get obj1 id2) (get obj2 id2)))
      (t/is (= (count obj1) (count obj2)))
      (t/is (= (hash obj1) (hash obj2)))))
  )

(defspec internal-encode-decode 25
  (props/for-all
   [data (->> (gen/map gen/uuid (s/gen ::cts/shape))
              (gen/not-empty))]
   (let [obj1 (omap/wrap data)
         obj2 (omap/create (deref obj1))
         obj3 (assoc obj2 uuid/zero 1)
         obj4 (omap/create (deref obj3))]
     ;; (app.common.pprint/pprint data)
     (t/is (= (hash obj1) (hash obj2)))
     (t/is (not= (hash obj2) (hash obj3)))
     (t/is (bytes? (deref obj3)))
     (t/is (pos? (alength (deref obj3))))
     (t/is (= (hash obj3) (hash obj4))))))

(defspec fressian-encode-decode 25
  (props/for-all
   [data (->> (gen/map gen/uuid (s/gen ::cts/shape))
              (gen/not-empty)
              (gen/fmap omap/wrap)
              (gen/fmap (fn [o] {:objects o})))]
   (let [res (-> data fres/encode fres/decode)]
     (t/is (contains? res :objects))
     (t/is (omap/objects-map? (:objects res)))
     (t/is (= (count (:objects data))
              (count (:objects res))))
     (t/is (= (hash (:objects data))
              (hash (:objects res)))))))

(defspec transit-encode-decode 25
  (props/for-all
   [data (->> (gen/map gen/uuid (s/gen ::cts/shape))
              (gen/not-empty)
              (gen/fmap omap/wrap)
              (gen/fmap (fn [o] {:objects o})))]
   (let [res (-> data transit/encode transit/decode)]
     ;; (app.common.pprint/pprint data)
     ;; (app.common.pprint/pprint res)
     (doseq [[k v] (:objects res)]
       (t/is (= v (get-in data [:objects k]))))

     (t/is (contains? res :objects))
     (t/is (contains? data :objects))

     (t/is (omap/objects-map? (:objects data)))
     (t/is (not (omap/objects-map? (:objects res))))

     (t/is (= (count (:objects data))
              (count (:objects res)))))))



