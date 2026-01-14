;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-object-test
  (:require
   [app.common.schema :as sm]
   [app.util.object :as obj]
   [cljs.pprint :refer [pprint]]
   [cljs.test :as t]))

(t/deftest getters-and-setters
  (let [val (volatile! nil)
        obj (obj/reify {:name "Foo"}
              :value
              {:get (fn [] @val)
               :set (fn [o] (vswap! val (constantly o)))})]

    (t/is (nil? (.-value obj)))
    (set! (.-value obj) 2)
    (t/is (= 2 (.-value obj)))
    (t/is (= 2 @val))))

(t/deftest getters-and-setters-access-this
  (let [val (volatile! nil)
        obj (obj/reify {:name "Foo"}
              :value
              {:get (fn []
                      (this-as self
                               self))
               :set (fn [o]
                      (this-as self
                               (vreset! val self)))})]

    (t/is (identical? obj (.-value obj)))
    (set! (.-value obj) 1)
    (t/is (identical? obj @val))))

(t/deftest getters-and-setters-access-explicit-this
  (let [val1 (volatile! nil)
        val2 (volatile! nil)
        obj  (obj/reify {:name "Foo"}
               :value
               {:this true
                :get (fn [this]
                       (this-as self (vreset! val1 self))
                       (vreset! val2 this)
                       this)
                :set (fn [this o]
                       (this-as self (vreset! val1 self))
                       (vreset! val2 this))})]

    (t/is (identical? obj (.-value obj)))
    (t/is (identical? obj @val1))
    (t/is (identical? obj @val2))

    (vreset! val1 nil)
    (vreset! val2 nil)
    (set! (.-value obj) 1)

    (t/is (identical? obj @val1))
    (t/is (identical? obj @val2))))

(t/deftest functions-with-map-syntax
  (let [val (volatile! nil)
        obj (obj/reify {:name "Foo"}
              :sum
              {:fn (fn [a b]
                     (this-as self (vreset! val self))
                     (+ a b))})]

    (t/is (= 3 (.sum obj 1 2)))
    (t/is (identical? obj @val))))

(t/deftest functions-with-short-syntax
  (let [val (volatile! nil)
        obj (obj/reify {:name "Foo"}
              :sum
              (fn [a b]
                (this-as self (vreset! val self))
                (+ a b)))]

    (t/is (= 3 (.sum obj 1 2)))
    (t/is (identical? obj @val))))

(t/deftest functions-with-schema
  (let [val (volatile! nil)
        obj (obj/reify {:name "Foo"}
              :sum
              {:schema [:cat ::sm/int ::sm/int]
               :fn (fn [a b]
                     (this-as self (vreset! val self))
                     (+ a b))})]
    (t/is (= 3 (.sum obj 1 2)))
    (t/is (= 3 (.sum obj 1 "2")))

    (t/is (true? (.propertyIsEnumerable obj "sum")))
    (t/is (thrown-with-msg? js/Error
                            #"check error"
                            (.sum obj 1 "a")))))

(t/deftest non-enumerable-props
  (let [val (volatile! nil)
        obj (obj/reify {:name "Foo"}
              :sum
              {:enumerable false
               :fn (fn [a b]
                     (this-as self (vreset! val self))
                     (+ a b))})]

    (t/is (false? (.propertyIsEnumerable obj "sum")))))
