;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.variant-test
  (:require
   [app.common.types.variant :as ctv]
   [clojure.test :as t]))

(t/deftest convert-between-variant-properties-maps-and-formulas
  (let [map-with-two-props           [{:name "border" :value "yes"} {:name "color" :value "gray"}]
        map-with-two-props-one-blank [{:name "border" :value "no"} {:name "color" :value ""}]
        map-with-two-props-dashes    [{:name "border" :value "no"} {:name "color" :value "--"}]
        map-with-one-prop            [{:name "border" :value "no"}]
        map-with-equal               [{:name "border" :value "yes color=yes"}]
        map-with-spaces              [{:name "border 1" :value "of course"}
                                      {:name "color 2" :value "dark gray"}
                                      {:name "background 3" :value "anoth€r co-lor"}]

        string-valid-with-two-props  "border=yes, color=gray"
        string-valid-with-one-prop   "border=no"
        string-valid-with-spaces     "border 1=of course, color 2=dark gray, background 3=anoth€r co-lor"
        string-valid-with-no-value   "border=no, color="
        string-valid-with-dashes     "border=no, color=--"
        string-valid-with-equal      "border=yes color=yes"

        string-invalid-empty         ""
        string-invalid-no-property-1 "=yes"
        string-invalid-no-property-2 "border=yes, =gray"
        string-invalid-no-equal-1    "border"
        string-invalid-no-equal-2    "border=yes, color"
        string-invalid-too-long-1    "this is a too long property name which should throw a validation error=yes"
        string-invalid-too-long-2    "border=this is a too long property name which should throw a validation error"]

    (t/testing "convert map to formula"
      (t/is (= (ctv/properties-map->formula map-with-two-props) string-valid-with-two-props))
      (t/is (= (ctv/properties-map->formula map-with-two-props-one-blank) string-valid-with-one-prop))
      (t/is (= (ctv/properties-map->formula map-with-spaces) string-valid-with-spaces)))

    (t/testing "convert formula to map"
      (t/is (= (ctv/properties-formula->map string-valid-with-two-props) map-with-two-props))
      (t/is (= (ctv/properties-formula->map string-valid-with-one-prop) map-with-one-prop))
      (t/is (= (ctv/properties-formula->map string-valid-with-no-value) map-with-one-prop))
      (t/is (= (ctv/properties-formula->map string-valid-with-dashes) map-with-two-props-dashes))
      (t/is (= (ctv/properties-formula->map string-valid-with-equal) map-with-equal))
      (t/is (= (ctv/properties-formula->map string-valid-with-spaces) map-with-spaces)))

    (t/testing "check if a formula is valid"
      (t/is (= (ctv/valid-properties-formula? string-valid-with-two-props) true))
      (t/is (= (ctv/valid-properties-formula? string-valid-with-one-prop) true))
      (t/is (= (ctv/valid-properties-formula? string-valid-with-spaces) true))
      (t/is (= (ctv/valid-properties-formula? string-valid-with-no-value) true))
      (t/is (= (ctv/valid-properties-formula? string-valid-with-dashes) true))
      (t/is (= (ctv/valid-properties-formula? string-invalid-empty) false))
      (t/is (= (ctv/valid-properties-formula? string-invalid-no-property-1) false))
      (t/is (= (ctv/valid-properties-formula? string-invalid-no-equal-1) false))
      (t/is (= (ctv/valid-properties-formula? string-invalid-no-property-2) false))
      (t/is (= (ctv/valid-properties-formula? string-invalid-no-equal-2) false))
      (t/is (= (ctv/valid-properties-formula? string-invalid-too-long-1) false))
      (t/is (= (ctv/valid-properties-formula? string-invalid-too-long-2) false)))))


(t/deftest find-properties
  (let [prev-props  [{:name "border" :value "yes"} {:name "color" :value "gray"}]
        upd-props-1 [{:name "border" :value "yes"}]
        upd-props-2 [{:name "border" :value "yes"} {:name "color" :value "blue"}]
        upd-props-3 [{:name "border" :value "yes"} {:name "color" :value "gray"} {:name "shadow" :value "large"}]
        upd-props-4 [{:name "color" :value "yellow"} {:name "shadow" :value "large"}]]

    (t/testing "a property to remove"
      (t/is (= (ctv/find-properties-to-remove prev-props upd-props-1)
               [{:name "color" :value "gray"}]))
      (t/is (= (ctv/find-properties-to-update prev-props upd-props-1)
               []))
      (t/is (= (ctv/find-properties-to-add prev-props upd-props-1)
               [])))

    (t/testing "a property to update"
      (t/is (= (ctv/find-properties-to-remove prev-props upd-props-2)
               []))
      (t/is (= (ctv/find-properties-to-update prev-props upd-props-2)
               [{:name "color" :value "blue"}]))
      (t/is (= (ctv/find-properties-to-add prev-props upd-props-2)
               [])))

    (t/testing "a property to add"
      (t/is (= (ctv/find-properties-to-remove prev-props upd-props-3)
               []))
      (t/is (= (ctv/find-properties-to-update prev-props upd-props-3)
               []))
      (t/is (= (ctv/find-properties-to-add prev-props upd-props-3)
               [{:name "shadow" :value "large"}])))

    (t/testing "properties to remove, update & add"
      (t/is (= (ctv/find-properties-to-remove prev-props upd-props-4)
               [{:name "border" :value "yes"}]))
      (t/is (= (ctv/find-properties-to-update prev-props upd-props-4)
               [{:name "color" :value "yellow"}]))
      (t/is (= (ctv/find-properties-to-add prev-props upd-props-4)
               [{:name "shadow" :value "large"}])))

    (t/testing "find property index"
      (t/is (= (ctv/find-index-for-property-name prev-props "border") 0))
      (t/is (= (ctv/find-index-for-property-name prev-props "color") 1)))))


(t/deftest compare-properties
  (let [props-1  [{:name "border" :value "yes"} {:name "color" :value "gray"}]
        props-2  [{:name "border" :value "yes"} {:name "color" :value "red"}]
        props-3  [{:name "border" :value "no"} {:name "color" :value "gray"}]]

    (t/testing "compare properties"
      (t/is (= (ctv/compare-properties [props-1 props-2])
               [{:name "border" :value "yes"} {:name "color" :value nil}]))
      (t/is (= (ctv/compare-properties [props-1 props-2 props-3])
               [{:name "border" :value nil} {:name "color" :value nil}]))
      (t/is (= (ctv/compare-properties [props-1 props-2 props-3] "&")
               [{:name "border" :value "&"} {:name "color" :value "&"}])))))


(t/deftest check-belong-same-variant
  (let [components-1 [{:variant-id "a variant"} {:variant-id "a variant"}]
        components-2 [{:variant-id "a variant"} {:variant-id "another variant"}]
        components-3 [{:variant-id "a variant"} {}]
        components-4 [{} {}]]

    (t/testing "check-belong-same-variant"
      (t/is (= (ctv/same-variant? components-1) true))
      (t/is (= (ctv/same-variant? components-2) false))
      (t/is (= (ctv/same-variant? components-3) false))
      (t/is (= (ctv/same-variant? components-4) false)))))
