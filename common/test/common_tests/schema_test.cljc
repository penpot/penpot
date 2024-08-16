;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.schema-test
  (:require
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [clojure.test :as t]))

(t/deftest test-set-of-email
  (t/testing "decoding"
    (let [candidate1 "a@b.com a@c.net"
          schema     [::sm/set ::sm/email]
          result1    (sm/decode schema candidate1 sm/string-transformer)
          result2    (sm/decode schema candidate1 sm/json-transformer)]
      (t/is (= result1 #{"a@b.com" "a@c.net"}))
      (t/is (= result2 #{"a@b.com" "a@c.net"}))))

  (t/testing "encoding"
    (let [candidate #{"a@b.com" "a@c.net"}
          schema    [::sm/set ::sm/email]
          result1   (sm/encode schema candidate sm/string-transformer)
          result2   (sm/decode schema candidate sm/json-transformer)]
      (t/is (= result1 "a@b.com, a@c.net"))
      (t/is (= result2 candidate))))

  (t/testing "validate"
    (let [candidate #{"a@b.com" "a@c.net"}
          schema    [::sm/set ::sm/email]]

      (t/is (true? (sm/validate schema candidate)))
      (t/is (true? (sm/validate schema #{})))
      (t/is (false? (sm/validate schema #{"a"})))))

  (t/testing "generate"
    (let [schema    [::sm/set ::sm/email]
          value     (sg/generate schema)]
      (t/is (true? (sm/validate schema (sg/generate schema)))))))
