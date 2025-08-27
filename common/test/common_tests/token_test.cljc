;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.token-test
  (:require
   [app.common.types.token :as token]
   [clojure.test :as t]))

(t/deftest valid-font-weight-variant
  (t/testing "numeric weights"
    (t/is (= {:weight "400"} (token/valid-font-weight-variant "400")))
    (t/is (= {:weight "700"} (token/valid-font-weight-variant "700")))
    (t/is (= {:weight "100"} (token/valid-font-weight-variant "100")))
    (t/is (= {:weight "900"} (token/valid-font-weight-variant "900"))))

  (t/testing "weight aliases"
    (t/is (= {:weight "400"} (token/valid-font-weight-variant "normal")))
    (t/is (= {:weight "400"} (token/valid-font-weight-variant "regular")))
    (t/is (= {:weight "700"} (token/valid-font-weight-variant "bold")))
    (t/is (= {:weight "300"} (token/valid-font-weight-variant "light")))
    (t/is (= {:weight "500"} (token/valid-font-weight-variant "medium")))
    (t/is (= {:weight "600"} (token/valid-font-weight-variant "semibold")))
    (t/is (= {:weight "600"} (token/valid-font-weight-variant "semi-bold")))
    (t/is (= {:weight "800"} (token/valid-font-weight-variant "extrabold")))
    (t/is (= {:weight "900"} (token/valid-font-weight-variant "black")))
    (t/is (= {:weight "950"} (token/valid-font-weight-variant "extra black"))))

  (t/testing "italic style"
    (t/is (= {:weight "400" :style "italic"} (token/valid-font-weight-variant "normal italic")))
    (t/is (= {:weight "700" :style "italic"} (token/valid-font-weight-variant "bold italic")))
    (t/is (= {:weight "400" :style "italic"} (token/valid-font-weight-variant "400 italic")))
    (t/is (= {:weight "950" :style "italic"} (token/valid-font-weight-variant "extra black italic"))))

  (t/testing "case-insensitivity"
    (t/is (= {:weight "700"} (token/valid-font-weight-variant "BOLD")))
    (t/is (= {:weight "400"} (token/valid-font-weight-variant "Normal")))
    (t/is (= {:weight "400" :style "italic"} (token/valid-font-weight-variant "NORMAL ITALIC"))))

  (t/testing "invalid values"
    (t/is (nil? (token/valid-font-weight-variant "invalid")))
    (t/is (nil? (token/valid-font-weight-variant "invalid italic")))
    (t/is (nil? (token/valid-font-weight-variant "999")))
    (t/is (nil? (token/valid-font-weight-variant "")))))
