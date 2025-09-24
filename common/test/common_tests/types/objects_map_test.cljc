;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.objects-map-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.json :as json]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.transit :as transit]
   [app.common.types.objects-map :as omap]
   [app.common.types.path :as path]
   [app.common.types.plugins :refer [schema:plugin-data]]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

(t/deftest basic-operations
  (t/testing "assoc"
    (let [id  (uuid/custom 0 1)
          id' (uuid/custom 0 2)
          obj (-> (omap/create) (assoc id {:foo 1}))]
      (t/is (not= id id'))
      (t/is (not (contains? obj id')))
      (t/is (contains? obj id))))

  (t/testing "assoc-with-non-uuid-keys"
    (let [obj (-> (omap/create)
                  (assoc :a {:foo 1})
                  (assoc :b {:bar 1}))]
      (t/is (not (contains? obj :c)))
      (t/is (contains? obj :a))
      (t/is (contains? obj :b))))

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
      (t/is (= 0 (count obj2))))))

(t/deftest internal-state
  (t/testing "modified & compact"
    (let [obj (-> (omap/create)
                  (assoc :a 1)
                  (assoc :b 2))]
      (t/is (= 2 (count obj)))
      (t/is (-> obj datafy :modified))
      (let [obj (omap/compact obj)]
        (t/is (not (-> obj datafy :modified))))))

  (t/testing "create from other"
    (let [obj1 (-> (omap/create)
                   (assoc :a {:foo 1})
                   (assoc :b {:bar 2}))
          obj2 (omap/create obj1)]

      (t/is (not (identical? obj1 obj2)))
      (t/is (= obj1 obj2))
      (t/is (= (hash obj1) (hash obj2)))
      (t/is (= (get obj1 :a) (get obj2 :a)))
      (t/is (= (get obj1 :b) (get obj2 :b))))))

(t/deftest creation-and-duplication
  (smt/check!
   (smt/for [data (->> (sg/map-of (sg/uuid) (sg/generator cts/schema:shape))
                       (sg/not-empty))]
     (let [obj1 (omap/wrap data)
           obj2 (omap/create obj1)]
       (and (= (hash obj1) (hash obj2))
            (= obj1 obj2))))
   {:num 100}))

#?(:clj
   (t/deftest fressian-encode-decode
     (smt/check!
      (smt/for [data (->> (sg/map-of (sg/uuid) (sg/generator cts/schema:shape))
                          (sg/not-empty)
                          (sg/fmap omap/wrap)
                          (sg/fmap (fn [o] {:objects o})))]

        (let [res (-> data fres/encode fres/decode)]
          (and (contains? res :objects)
               (omap/objects-map? (:objects res))
               (= res data))))
      {:num 100})))

(t/deftest transit-encode-decode
  (smt/check!
   (smt/for [data (->> (sg/map-of (sg/uuid) (sg/generator cts/schema:shape))
                       (sg/not-empty)
                       (sg/fmap omap/wrap)
                       (sg/fmap (fn [o] {:objects o})))]
     (let [res (-> data transit/encode-str transit/decode-str)]
       ;; (app.common.pprint/pprint data)
       ;; (app.common.pprint/pprint res)
       (and (every? (fn [[k v]]
                      (= v (get-in data [:objects k])))
                    (:objects res))
            (omap/objects-map? (:objects data))
            (omap/objects-map? (:objects res)))))
   {:num 100}))
