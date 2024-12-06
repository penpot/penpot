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


(t/deftest test-set-1
  (let [candidate-1 "cff4b058-ca31-8197-8005-32aeb2377d83, cff4b058-ca31-8197-8005-32aeb2377d82"
        candidate-2 ["cff4b058-ca31-8197-8005-32aeb2377d82",
                     "cff4b058-ca31-8197-8005-32aeb2377d83"]
        candidate-3 #{"cff4b058-ca31-8197-8005-32aeb2377d82", "cff4b058-ca31-8197-8005-32aeb2377d83"}
        candidate-4 [#uuid "cff4b058-ca31-8197-8005-32aeb2377d82"
                     #uuid "cff4b058-ca31-8197-8005-32aeb2377d83"]
        candidate-5 #{#uuid "cff4b058-ca31-8197-8005-32aeb2377d82"
                      #uuid "cff4b058-ca31-8197-8005-32aeb2377d83"}

        expected    candidate-5

        schema      [::sm/set ::sm/uuid]
        decode-s    (sm/decoder schema sm/string-transformer)
        decode-j    (sm/decoder schema sm/json-transformer)
        encode-s    (sm/encoder schema sm/string-transformer)
        encode-j    (sm/encoder schema sm/json-transformer)]


    (t/is (= expected (decode-s candidate-1)))
    (t/is (= expected (decode-s candidate-2)))
    (t/is (= expected (decode-s candidate-3)))
    (t/is (= expected (decode-s candidate-4)))
    (t/is (= expected (decode-s candidate-5)))

    (t/is (= candidate-1 (encode-s expected)))
    (t/is (= candidate-3 (encode-j expected)))))


(t/deftest test-vec-1
  (let [candidate-1 "cff4b058-ca31-8197-8005-32aeb2377d83, cff4b058-ca31-8197-8005-32aeb2377d82"
        candidate-2 ["cff4b058-ca31-8197-8005-32aeb2377d83",
                     "cff4b058-ca31-8197-8005-32aeb2377d82"]
        candidate-3 #{"cff4b058-ca31-8197-8005-32aeb2377d82", "cff4b058-ca31-8197-8005-32aeb2377d83"}
        candidate-4 [#uuid "cff4b058-ca31-8197-8005-32aeb2377d83"
                     #uuid "cff4b058-ca31-8197-8005-32aeb2377d82"]
        candidate-5 #{#uuid "cff4b058-ca31-8197-8005-32aeb2377d82"
                      #uuid "cff4b058-ca31-8197-8005-32aeb2377d83"}

        expected    candidate-4

        schema      [::sm/vec ::sm/uuid]
        decode-s    (sm/decoder schema sm/string-transformer)
        decode-j    (sm/decoder schema sm/json-transformer)
        encode-s    (sm/encoder schema sm/string-transformer)
        encode-j    (sm/encoder schema sm/json-transformer)]


    (t/is (= expected (decode-s candidate-1)))
    (t/is (= expected (decode-s candidate-2)))
    (t/is (= expected (decode-s candidate-3)))
    (t/is (= expected (decode-s candidate-4)))
    (t/is (= expected (decode-s candidate-5)))

    (t/is (= candidate-1 (encode-s expected)))
    (t/is (= candidate-2 (encode-j expected)))))


