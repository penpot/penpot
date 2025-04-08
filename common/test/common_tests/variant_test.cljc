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

        string-valid-with-two-props  "border=yes, color=gray"
        string-valid-with-one-prop   "border=no"
        string-invalid               "border=yes, color="]

    (t/testing "convert map to string"
      (t/is (= (ctv/properties-map-to-string map-with-two-props) string-valid-with-two-props))
      (t/is (= (ctv/properties-map-to-string map-with-two-props-one-blank) string-valid-with-one-prop)))

    (t/testing "convert string to map"
      (t/is (= (ctv/properties-string-to-map string-valid-with-two-props) map-with-two-props))
      (t/is (= (ctv/properties-string-to-map string-valid-with-one-prop) map-with-one-prop)))

    (t/testing "check if a string is valid"
      (t/is (= (ctv/valid-properties-string? string-valid-with-two-props) true))
      (t/is (= (ctv/valid-properties-string? string-valid-with-one-prop) true))
      (t/is (= (ctv/valid-properties-string? string-invalid) false)))))
