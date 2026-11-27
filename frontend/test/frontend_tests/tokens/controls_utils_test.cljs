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

(t/deftest pasted-text-test
  (t/testing "the paste replaces what is selected"
    (t/is (= "ab-CD-ef" (csu/pasted-text "abXXef" 2 4 "-CD-")))
    (t/is (= "new" (csu/pasted-text "old" 0 3 "new"))))

  (t/testing "without a selection it goes where the cursor is"
    (t/is (= "aXb" (csu/pasted-text "ab" 1 1 "X")))
    (t/is (= "abX" (csu/pasted-text "ab" 2 2 "X")))
    (t/is (= "X" (csu/pasted-text "" 0 0 "X")))))

(defn- paste-event
  "A paste event whose clipboard holds `text`. Records the type of data
  that is asked for in `asked`."
  [text asked]
  #js {:clipboardData #js {:getData (fn [type] (reset! asked type) text)}})

(t/deftest converted-paste-value-test
  (let [input #js {:value "a{x}b" :selectionStart 1 :selectionEnd 4}
        convert (fn [text] (when (= "css" text) "TOKENSCRIPT"))]
    (t/testing "the converted plain text of the paste replaces the selection"
      (let [asked (atom nil)]
        (t/is (= "aTOKENSCRIPTb" (csu/converted-paste-value convert input (paste-event "css" asked))))
        (t/is (= "text" @asked))))

    (t/testing "text that is not converted is pasted as it is"
      (t/is (nil? (csu/converted-paste-value convert input (paste-event "other" (atom nil))))))

    (t/testing "without a conversion or clipboard data nothing is converted"
      (t/is (nil? (csu/converted-paste-value nil input (paste-event "css" (atom nil)))))
      (t/is (nil? (csu/converted-paste-value convert input #js {}))))))
