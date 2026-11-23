;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-warnings-test
  (:require
   [app.main.data.workspace.tokens.warnings :as wtw]
   [app.util.i18n :refer [tr]]
   [cljs.test :as t :include-macros true]))

(t/deftest humanize-warnings
  (t/testing "an out of range opacity shows the resolved value and the allowed range"
    (t/is (= [(str (tr "workspace.tokens.resolved-value" 1.5) ".\n" (tr "errors.tokens.opacity-range"))]
             (wtw/humanize-warnings [(wtw/warning-with-value :warning.style-dictionary/invalid-referenced-token-value-opacity 1.5)]))))

  (t/testing "an out of range stroke width shows the resolved value and the allowed range"
    (t/is (= [(str (tr "workspace.tokens.resolved-value" -2) ".\n" (tr "errors.tokens.stroke-width-range"))]
             (wtw/humanize-warnings [(wtw/warning-with-value :warning.style-dictionary/invalid-referenced-token-value-stroke-width -2)]))))

  (t/testing "an unknown warning code shows the unknown warning message"
    (t/is (= ["Unknown warning"]
             (wtw/humanize-warnings [(wtw/warning-with-value :warning.style-dictionary/not-a-code 1)]))))

  (t/testing "a warning that is already a message shows as is"
    (t/is (= ["Plain message"] (wtw/humanize-warnings ["Plain message"]))))

  (t/testing "no warnings give no messages"
    (t/is (empty? (wtw/humanize-warnings nil)))))
