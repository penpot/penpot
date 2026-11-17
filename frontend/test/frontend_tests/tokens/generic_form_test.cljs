;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.generic-form-test
  (:require
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [cljs.test :as t :include-macros true]))

(def ^:private initial-tab @#'generic/initial-tab)

(t/deftest initial-tab-test
  (t/testing "a token that references another one opens on the reference tab"
    (t/is (= :reference (initial-tab {:value "{color.brand}"} nil)))
    (t/is (= :reference (initial-tab {:value "{typography.base}"} :composite))))

  (t/testing "a composite token with a string as its value is a reference or tokenscript"
    (t/is (= :reference (initial-tab {:value "linear-gradient(90deg, #f00 0%, #00f 100%)"} :composite))))

  (t/testing "a composite token with fields opens on the composite tab"
    (t/is (= :composite (initial-tab {:value {:font-size "16"}} :composite)))
    (t/is (= :composite (initial-tab {:value {:kind "linear" :stops []}} :composite))))

  (t/testing "a new token opens on the composite tab"
    (t/is (= :composite (initial-tab nil :composite)))
    (t/is (= :composite (initial-tab {:type :gradient} :composite))))

  (t/testing "a token with a plain value is not a reference unless it references one"
    (t/is (= :composite (initial-tab {:value "16"} nil)))))
