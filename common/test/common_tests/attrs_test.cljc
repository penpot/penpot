;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.attrs-test
  (:require
   [app.common.attrs :as attrs]
   [clojure.test :as t]))

(t/deftest get-attrs-multi-same-value
  (t/testing "returns value when all objects have the same attribute value"
    (let [objs [{:attr "red"}
                {:attr "red"}
                {:attr "red"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr "red"} result))))

  (t/testing "returns nil when all objects have nil value"
    (let [objs [{:attr nil}
                {:attr nil}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr nil} result)))))

(t/deftest get-attrs-multi-different-values
  (t/testing "returns :multiple when objects have different concrete values"
    (let [objs [{:attr "red"}
                {:attr "blue"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr :multiple} result)))))

(t/deftest get-attrs-multi-missing-key
  (t/testing "returns value when one object has the attribute and another doesn't"
    (let [objs [{:attr "red"}
                {:other "value"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr "red"} result))))

  (t/testing "returns value when one object has UUID and another is missing"
    (let [uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
          objs [{:attr uuid}
                {:other "value"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr uuid} result))))

  (t/testing "returns :multiple when some objects have the key and some don't"
    (let [objs [{:attr "red"}
                {:other "value"}
                {:attr "blue"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr :multiple} result))))

  (t/testing "returns nil when one object has nil and another is missing"
    (let [objs [{:attr nil}
                {:other "value"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {:attr nil} result)))))

(t/deftest get-attrs-multi-all-missing
  (t/testing "all missing → attribute NOT included in result"
    (let [objs [{:other "value"}
                {:different "data"}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {} result)
            "Attribute should not be in result when all objects are missing")))

  (t/testing "all missing with empty maps → attribute NOT included"
    (let [objs [{} {}]
          result (attrs/get-attrs-multi objs [:attr])]
      (t/is (= {} result)
            "Attribute should not be in result"))))

(t/deftest get-attrs-multi-multiple-attributes
  (t/testing "handles multiple attributes with different merge results"
    (let [objs [{:attr1 "red" :attr2 "blue"}
                {:attr1 "red" :attr2 "green"}
                {:attr1 "red"}]  ; :attr2 missing
          result (attrs/get-attrs-multi objs [:attr1 :attr2])]
      (t/is (= {:attr1 "red" :attr2 :multiple} result))))

  (t/testing "handles mixed scenarios: same, different, and missing"
    (let [uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
          uuid2 #uuid "550e8400-e29b-41d4-a716-446655440001"
          objs [{:id :a :ref uuid}
                {:id :b :ref uuid2}
                {:id :c}]  ; :ref missing
          result (attrs/get-attrs-multi objs [:id :ref])]
      (t/is (= {:id :multiple :ref :multiple} result)))))

(t/deftest get-attrs-multi-typography-ref-id-scenario
  (t/testing "the specific bug scenario: typography-ref-id with UUID vs missing"
    (let [uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
          ;; Shape 1 has typography-ref-id with a UUID
          shape1 {:id :shape1 :typography-ref-id uuid}
          ;; Shape 2 does NOT have typography-ref-id at all
          shape2 {:id :shape2}
          result (attrs/get-attrs-multi [shape1 shape2] [:typography-ref-id])]
      (t/is (= {:typography-ref-id uuid} result))))

  (t/testing "both shapes missing → attribute NOT included in result"
    (let [shape1 {:id :shape1}
          shape2 {:id :shape2}
          result (attrs/get-attrs-multi [shape1 shape2] [:typography-ref-id])]
      (t/is (= {} result)
            "Expected empty map when all shapes are missing the attribute"))))

(t/deftest get-attrs-multi-bug-missing-vs-present
  (t/testing "BUG FIXED: one shape has :typography-ref-id, other does NOT → returns uuid"
    (let [uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
          shape1 {:id :shape1 :typography-ref-id uuid}
          shape2 {:id :shape2}
          result (attrs/get-attrs-multi [shape1 shape2] [:typography-ref-id])]
      (t/is (= {:typography-ref-id uuid} result))))

  (t/testing "both missing → empty map (attribute not in result)"
    (let [shape1 {:id :shape1}
          shape2 {:id :shape2}
          result (attrs/get-attrs-multi [shape1 shape2] [:typography-ref-id])]
      (t/is (= {} result)
            "Expected empty map when all shapes are missing the attribute")))

  (t/testing "both equal values → return the value"
    (let [uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
          shape1 {:id :shape1 :typography-ref-id uuid}
          shape2 {:id :shape2 :typography-ref-id uuid}
          result (attrs/get-attrs-multi [shape1 shape2] [:typography-ref-id])]
      (t/is (= {:typography-ref-id uuid} result))))

  (t/testing "different values → return :multiple"
    (let [uuid1 #uuid "550e8400-e29b-41d4-a716-446655440000"
          uuid2 #uuid "550e8400-e29b-41d4-a716-446655440001"
          shape1 {:id :shape1 :typography-ref-id uuid1}
          shape2 {:id :shape2 :typography-ref-id uuid2}
          result (attrs/get-attrs-multi [shape1 shape2] [:typography-ref-id])]
      (t/is (= {:typography-ref-id :multiple} result)))))

(t/deftest get-attrs-multi-default-equal
  (t/testing "numbers use close? for equality"
    (let [objs [{:value 1.0}
                {:value 1.0000001}]
          result (attrs/get-attrs-multi objs [:value])]
      (t/is (= {:value 1.0} result)
            "Numbers within tolerance should be considered equal")))

  (t/testing "different floating point positions beyond tolerance are :multiple"
    (let [objs [{:x -26}
                {:x -153}]
          result (attrs/get-attrs-multi objs [:x])]
      (t/is (= {:x :multiple} result)
            "Different positions should be :multiple"))))