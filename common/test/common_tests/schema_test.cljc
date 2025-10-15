;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.schema-test
  (:require
   [app.common.data :as d]
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

  (t/testing "validate 2"
    (let [candidate-1 ["a@b.com" "a@c.net"]
          candidate-2 (into #{} candidate-1)
          candidate-3 (into (d/ordered-set) candidate-1)
          candidate-4 #{"a@b.com"}
          candidate-5 (d/ordered-set "a@b.com")
          schema-1    [::sm/set ::sm/email]
          schema-2    [::sm/set {:ordered true} ::sm/email]
          schema-3    [::sm/set {:ordered true :min 1} ::sm/email]
          schema-4    [::sm/set {:min 1} ::sm/email]
          schema-5    [::sm/set {:ordered true :max 1} ::sm/email]
          schema-6    [::sm/set {:ordered true :min 1 :max 2} ::sm/email]
          schema-7    [::sm/set {:min 1 :max 2} ::sm/email]]

      (t/is (false? (sm/validate schema-1 [])))
      (t/is (false? (sm/validate schema-1 candidate-1)))
      (t/is (true?  (sm/validate schema-1 candidate-2)))
      (t/is (true?  (sm/validate schema-1 candidate-3)))

      (t/is (false? (sm/validate schema-2 [])))
      (t/is (false? (sm/validate schema-2 candidate-1)))
      (t/is (false? (sm/validate schema-2 candidate-2)))
      (t/is (true?  (sm/validate schema-2 candidate-3)))

      (t/is (false? (sm/validate schema-3 [])))
      (t/is (false? (sm/validate schema-3 candidate-1)))
      (t/is (false? (sm/validate schema-3 candidate-2)))
      (t/is (true?  (sm/validate schema-3 candidate-3)))
      (t/is (false? (sm/validate schema-3 candidate-4)))
      (t/is (true?  (sm/validate schema-3 candidate-5)))
      (t/is (false? (sm/validate schema-3 (d/ordered-set))))

      (t/is (false? (sm/validate schema-4 [])))
      (t/is (false? (sm/validate schema-4 candidate-1)))
      (t/is (true?  (sm/validate schema-4 candidate-2)))
      (t/is (true?  (sm/validate schema-4 candidate-3)))
      (t/is (true?  (sm/validate schema-4 candidate-4)))
      (t/is (true?  (sm/validate schema-4 candidate-5)))
      (t/is (false? (sm/validate schema-4 (d/ordered-set))))
      (t/is (false? (sm/validate schema-4 #{})))

      (t/is (false? (sm/validate schema-5 [])))
      (t/is (false? (sm/validate schema-5 candidate-1)))
      (t/is (false? (sm/validate schema-5 candidate-2)))
      (t/is (false? (sm/validate schema-5 candidate-3)))
      (t/is (false? (sm/validate schema-5 candidate-4)))
      (t/is (true?  (sm/validate schema-5 candidate-5)))
      (t/is (true?  (sm/validate schema-5 (d/ordered-set))))
      (t/is (false? (sm/validate schema-5 #{})))

      (t/is (false? (sm/validate schema-6 [])))
      (t/is (false? (sm/validate schema-6 candidate-1)))
      (t/is (false? (sm/validate schema-6 candidate-2)))
      (t/is (true?  (sm/validate schema-6 candidate-3)))
      (t/is (false? (sm/validate schema-6 candidate-4)))
      (t/is (true?  (sm/validate schema-6 candidate-5)))
      (t/is (false? (sm/validate schema-6 (d/ordered-set))))
      (t/is (false? (sm/validate schema-6 #{})))
      (t/is (false? (sm/validate schema-6 (conj candidate-3 "r@r.com"))))

      (t/is (false? (sm/validate schema-7 [])))
      (t/is (false? (sm/validate schema-7 candidate-1)))
      (t/is (true?  (sm/validate schema-7 candidate-2)))
      (t/is (true?  (sm/validate schema-7 candidate-3)))
      (t/is (true?  (sm/validate schema-7 candidate-4)))
      (t/is (true?  (sm/validate schema-7 candidate-5)))
      (t/is (false? (sm/validate schema-7 (d/ordered-set))))
      (t/is (false? (sm/validate schema-7 #{})))
      (t/is (false? (sm/validate schema-7 (conj candidate-2 "r@r.com"))))
      (t/is (false? (sm/validate schema-7 (conj candidate-3 "r@r.com"))))))

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


