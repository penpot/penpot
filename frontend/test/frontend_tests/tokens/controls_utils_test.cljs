;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.controls-utils-test
  (:require
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [cljs.test :as t :include-macros true]))

(t/deftest visible-hint-test
  (let [resolved {:type "hint" :message "Resolved value: #ff0000"}
        error    {:type "error" :message "Missing token"}]
    (t/testing "the hint with the resolved value shows unless the form hides it"
      (t/is (= resolved (csu/visible-hint resolved nil)))
      (t/is (= resolved (csu/visible-hint resolved false)))
      (t/is (= {} (csu/visible-hint resolved true))))

    (t/testing "an error shows also when the resolved value is hidden"
      (t/is (= error (csu/visible-hint error true)))
      (t/is (= error (csu/visible-hint error nil))))

    (t/testing "no hint stays no hint"
      (t/is (= {} (csu/visible-hint {} true)))
      (t/is (= {} (csu/visible-hint {} nil))))))

(t/deftest ramp-open-test
  (t/testing "a form that decides for its inputs says which ramp is open"
    (t/is (true? (csu/ramp-open? true false)))
    (t/is (false? (csu/ramp-open? false true))))

  (t/testing "an input without such a form is open after its swatch was clicked"
    (t/is (true? (csu/ramp-open? nil true)))
    (t/is (false? (csu/ramp-open? nil false)))))

(t/deftest ramp-color-test
  (t/testing "a color shows itself"
    (t/is (= "#ff0000" (csu/ramp-color "#ff0000" "#00ff00")))
    (t/is (= "rgba(255, 0, 0, 0.5)" (csu/ramp-color "rgba(255, 0, 0, 0.5)" "#00ff00"))))

  (t/testing "a reference shows the color it resolves to"
    (t/is (= "#00ff00" (csu/ramp-color "{color.brand}" "#00ff00")))
    (t/is (= "#00ff00" (csu/ramp-color "" "#00ff00")))))

(t/deftest filter-tokens-for-input-test
  (let [tokens (delay {:color   [{:name "red"}]
                       :gradient [{:name "brand"}]
                       :spacing [{:name "sm"}]})
        types  (fn [input] (some-> @(csu/filter-tokens-for-input tokens input) keys vec))]
    (t/testing "a fill and a stroke take color and gradient tokens"
      (t/is (= [:color :gradient] (types :fill)))
      (t/is (= [:color :gradient] (types :stroke-color)))
      (t/is (= [:color :gradient] (types :color-selection))))

    (t/testing "the canvas background has no gradient"
      (t/is (= [:color] (types :canvas))))

    (t/testing "an input takes the tokens of its own types"
      (t/is (= [:spacing] (types :row-gap)))
      (t/is (nil? (types :opacity))))))
