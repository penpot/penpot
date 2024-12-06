;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.token-form-test
  (:require
   [app.main.ui.workspace.tokens.form :as wtf]
   [cljs.test :as t :include-macros true]
   [malli.core :as m]))

(t/deftest test-valid-token-name-schema
  ;; Allow regular namespace token names
  (t/is (some? (m/validate wtf/valid-token-name-schema "Foo")))
  (t/is (some? (m/validate wtf/valid-token-name-schema "foo")))
  (t/is (some? (m/validate wtf/valid-token-name-schema "FOO")))
  (t/is (some? (m/validate wtf/valid-token-name-schema "Foo.Bar.Baz")))
  ;; Allow trailing tokens
  (t/is (nil? (m/validate wtf/valid-token-name-schema "Foo.Bar.Baz....")))
  ;; Disallow multiple separator dots
  (t/is (nil? (m/validate wtf/valid-token-name-schema "Foo..Bar.Baz")))
  ;; Disallow any special characters
  (t/is (nil? (m/validate wtf/valid-token-name-schema "Hey Foo.Bar")))
  (t/is (nil? (m/validate wtf/valid-token-name-schema "Hey😈Foo.Bar")))
  (t/is (nil? (m/validate wtf/valid-token-name-schema "Hey%Foo.Bar"))))
