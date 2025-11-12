;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.token-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest test-valid-token-name-schema
  ;; Allow regular namespace token names
  (t/is (true? (sm/validate cto/token-name-ref "Foo")))
  (t/is (true? (sm/validate cto/token-name-ref "foo")))
  (t/is (true? (sm/validate cto/token-name-ref "FOO")))
  (t/is (true? (sm/validate cto/token-name-ref "Foo.Bar.Baz")))
  ;; Disallow trailing tokens
  (t/is (false? (sm/validate cto/token-name-ref "Foo.Bar.Baz....")))
  ;; Disallow multiple separator dots
  (t/is (false? (sm/validate cto/token-name-ref "Foo..Bar.Baz")))
  ;; Disallow any special characters
  (t/is (false? (sm/validate cto/token-name-ref "Hey Foo.Bar")))
  (t/is (false? (sm/validate cto/token-name-ref "Hey😈Foo.Bar")))
  (t/is (false? (sm/validate cto/token-name-ref "Hey%Foo.Bar"))))
