;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.spec-test
  (:require
   [app.common.spec :as spec]
   [clojure.test :as t]))

(t/deftest valid-emails
  (t/testing "accepts well-formed email addresses"
    (doseq [email ["user@domain.com"
                   "user.name@domain.com"
                   "user+tag@domain.com"
                   "user-name@domain.com"
                   "user_name@domain.com"
                   "user123@domain.com"
                   "USER@DOMAIN.COM"
                   "u@domain.io"
                   "user@sub.domain.com"
                   "user@domain.co.uk"
                   "user@domain.dev"
                   "a@bc.co"]]
      (t/is (some? (spec/parse-email email)) (str "should accept: " email)))))

(t/deftest rejects-invalid-local-part
  (t/testing "rejects local part starting with a dot"
    (t/is (nil? (spec/parse-email ".user@domain.com"))))

  (t/testing "rejects local part with consecutive dots"
    (t/is (nil? (spec/parse-email "user..name@domain.com"))))

  (t/testing "rejects local part with spaces"
    (t/is (nil? (spec/parse-email "us er@domain.com"))))

  (t/testing "rejects local part with comma"
    (t/is (nil? (spec/parse-email "user,name@domain.com")))
    (t/is (nil? (spec/parse-email ",user@domain.com"))))

  (t/testing "rejects empty local part"
    (t/is (nil? (spec/parse-email "@domain.com")))))

(t/deftest rejects-invalid-domain
  (t/testing "rejects domain starting with a dot"
    (t/is (nil? (spec/parse-email "user@.domain.com"))))

  (t/testing "rejects domain part with comma"
    (t/is (nil? (spec/parse-email "user@domain,com")))
    (t/is (nil? (spec/parse-email "user@,domain.com"))))

  (t/testing "rejects domain with consecutive dots"
    (t/is (nil? (spec/parse-email "user@sub..domain.com"))))

  (t/testing "rejects label starting with hyphen"
    (t/is (nil? (spec/parse-email "user@-domain.com"))))

  (t/testing "rejects label ending with hyphen"
    (t/is (nil? (spec/parse-email "user@domain-.com"))))

  (t/testing "rejects TLD shorter than 2 chars"
    (t/is (nil? (spec/parse-email "user@domain.c"))))

  (t/testing "rejects domain without a dot"
    (t/is (nil? (spec/parse-email "user@domain"))))

  (t/testing "rejects domain with spaces"
    (t/is (nil? (spec/parse-email "user@do main.com"))))

  (t/testing "rejects domain ending with a dot"
    (t/is (nil? (spec/parse-email "user@domain.")))))

(t/deftest rejects-invalid-structure
  (t/testing "rejects nil"
    (t/is (nil? (spec/parse-email nil))))

  (t/testing "rejects empty string"
    (t/is (nil? (spec/parse-email ""))))

  (t/testing "rejects string without @"
    (t/is (nil? (spec/parse-email "userdomain.com"))))

  (t/testing "rejects string with multiple @"
    (t/is (nil? (spec/parse-email "user@@domain.com")))
    (t/is (nil? (spec/parse-email "us@er@domain.com"))))

  (t/testing "rejects empty domain"
    (t/is (nil? (spec/parse-email "user@")))))
