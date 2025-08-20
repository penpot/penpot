;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.util-objects-map-test
  (:require
   [app.common.fressian :as fres]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.transit :as transit]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.util.objects-map :as omap]
   [backend-tests.helpers :as th]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as t]
   [clojure.test.check.generators :as cg]))

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
      (t/is (thrown? IllegalArgumentException (assoc obj :foo "bar"))))))

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
      (t/is (= (hash obj1) (hash obj2))))))

(t/deftest internal-encode-decode
  (smt/check!
   (smt/for [data (->> (cg/map cg/uuid (sg/generator cts/schema:shape))
                       (cg/not-empty))]
     (let [obj1 (omap/wrap data)
           obj2 (omap/create (deref obj1))
           obj3 (assoc obj2 uuid/zero 1)
           obj4 (omap/create (deref obj3))]
       ;; (app.common.pprint/pprint data)

       (and (= (hash obj1) (hash obj2))
            (not= (hash obj2) (hash obj3))
            (bytes? (deref obj3))
            (pos? (alength (deref obj3)))
            (= (hash obj3) (hash obj4)))))
   {:num 50}))

(t/deftest fressian-encode-decode
  (smt/check!
   (smt/for [data (->> (cg/map cg/uuid (sg/generator cts/schema:shape))
                       (cg/not-empty)
                       (cg/fmap omap/wrap)
                       (cg/fmap (fn [o] {:objects o})))]

     (let [res (-> data fres/encode fres/decode)]
       (and (contains? res :objects)
            (omap/objects-map? (:objects res))
            (= (count (:objects data))
               (count (:objects res)))
            (= (hash (:objects data))
               (hash (:objects res))))))
   {:num 50}))

(t/deftest transit-encode-decode
  (smt/check!
   (smt/for [data (->> (cg/map cg/uuid (sg/generator cts/schema:shape))
                       (cg/not-empty)
                       (cg/fmap omap/wrap)
                       (cg/fmap (fn [o] {:objects o})))]
     (let [res (-> data transit/encode transit/decode)]
       ;; (app.common.pprint/pprint data)
       ;; (app.common.pprint/pprint res)
       (and (every? (fn [[k v]]
                      (= v (get-in data [:objects k])))
                    (:objects res))
            (contains? res :objects)
            (contains? data :objects)
            (omap/objects-map? (:objects data))
            (not (omap/objects-map? (:objects res)))
            (= (count (:objects data))
               (count (:objects res))))))
   {:num 50}))


