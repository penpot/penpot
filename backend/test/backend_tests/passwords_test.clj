;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.passwords-test
  (:require
   [app.auth.passwords :as passwords]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(defn- run-validation
  [password]
  (try
    (passwords/validate-password password)
    nil
    (catch Throwable e
      e)))

(t/deftest validate-password-accepts-strong-password
  (t/is (nil? (run-validation "Str0ng!Pass"))))

(t/deftest validate-password-rejects-too-short-password
  (let [error (run-validation "Ab1!x")]
    (t/is (th/ex-of-code? error :weak-password))
    (t/is (= ["errors.weak-password.too-short"] (:details (ex-data error))))))

(t/deftest validate-password-rejects-missing-lowercase
  (let [error (run-validation "ABCDEFG1!")]
    (t/is (th/ex-of-code? error :weak-password))
    (t/is (= ["errors.weak-password.insufficient-lowercase"]
             (:details (ex-data error))))))

(t/deftest validate-password-rejects-missing-uppercase
  (let [error (run-validation "abcdefg1!")]
    (t/is (th/ex-of-code? error :weak-password))
    (t/is (= ["errors.weak-password.insufficient-uppercase"]
             (:details (ex-data error))))))

(t/deftest validate-password-rejects-missing-digit
  (let [error (run-validation "Abcdefgh!")]
    (t/is (th/ex-of-code? error :weak-password))
    (t/is (= ["errors.weak-password.insufficient-digits"]
             (:details (ex-data error))))))

(t/deftest validate-password-rejects-missing-special
  (let [error (run-validation "Abcdefgh1")]
    (t/is (th/ex-of-code? error :weak-password))
    (t/is (= ["errors.weak-password.insufficient-special"]
             (:details (ex-data error))))))