;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.variant-test
  (:require
   [app.common.types.variant :as ctv]
   [clojure.test :as t]))

(t/deftest convert-between-variant-properties-maps-and-strings
  (let [map-with-two-props           [{:name "border" :value "yes"} {:name "color" :value "gray"}]
        map-with-two-props-one-blank [{:name "border" :value "no"} {:name "color" :value ""}]
        map-with-one-prop            [{:name "border" :value "no"}]
        map-with-spaces              [{:name "border 1" :value "of course"} {:name "color 2" :value "dark gray"}]

        string-valid-with-two-props  "border=yes, color=gray"
        string-valid-with-one-prop   "border=no"
        string-valid-with-spaces     "border 1=of course, color 2=dark gray"
        string-invalid               "border=yes, color="]

    (t/testing "convert map to string"
      (t/is (= (ctv/properties-map-to-string map-with-two-props) string-valid-with-two-props))
      (t/is (= (ctv/properties-map-to-string map-with-two-props-one-blank) string-valid-with-one-prop))
      (t/is (= (ctv/properties-map-to-string map-with-spaces) string-valid-with-spaces)))

    (t/testing "convert string to map"
      (t/is (= (ctv/properties-string-to-map string-valid-with-two-props) map-with-two-props))
      (t/is (= (ctv/properties-string-to-map string-valid-with-one-prop) map-with-one-prop))
      (t/is (= (ctv/properties-string-to-map string-valid-with-spaces) map-with-spaces)))

    (t/testing "check if a string is valid"
      (t/is (= (ctv/valid-properties-string? string-valid-with-two-props) true))
      (t/is (= (ctv/valid-properties-string? string-valid-with-one-prop) true))
      (t/is (= (ctv/valid-properties-string? string-valid-with-spaces) true))
      (t/is (= (ctv/valid-properties-string? string-invalid) false)))))


(t/deftest compare-property-maps
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
