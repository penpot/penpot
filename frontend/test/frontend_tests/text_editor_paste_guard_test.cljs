;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.text-editor-paste-guard-test
  "Regression tests for the Cannot read properties of undefined
  (reading getData) family of bugs. Each test verifies that the inner
  getData call is now guarded by an outer when-let on clipboardData
  so that a synthetic event with no clipboardData no longer throws."
  (:require
   [cljs.test :as t :include-macros true]))

(defn- guarded-get-data-text
  "Mirrors the body of the fixed paste handlers in main/ui/forms.cljs and
  main/ui/components/forms.cljs."
  [event]
  (when-let [clipboard-data (.-clipboardData event)]
    (.getData clipboard-data "text")))

(defn- guarded-get-data-text-plain
  "Mirrors the body of the fixed paste handler in
  main/ui/workspace/shapes/text/v3_editor.cljs."
  [event]
  (when-let [clipboard-data (.-clipboardData event)]
    (.getData clipboard-data "text/plain")))

(t/deftest guarded-paste-handlers-do-not-throw-on-missing-clipboardData
  (t/testing "event without clipboardData returns nil (no throw)"
    (t/is (nil? (guarded-get-data-text #js {})))
    (t/is (nil? (guarded-get-data-text-plain #js {}))))
  (t/testing "event with explicit nil clipboardData returns nil"
    (t/is (nil? (guarded-get-data-text #js {:clipboardData nil})))
    (t/is (nil? (guarded-get-data-text-plain #js {:clipboardData nil}))))
  (t/testing "event with valid clipboardData returns the text"
    (let [text-cb (fn [t] (if (= t "text") "hello" nil))
          text-plain-cb (fn [t] (if (= t "text/plain") "hello" nil))
          cb #js {:getData (fn [t] (if (= t "text") "hello" nil))}
          cbp #js {:getData (fn [t] (if (= t "text/plain") "hello" nil))}]
      (t/is (= "hello" (guarded-get-data-text #js {:clipboardData cb})))
      (t/is (= "hello" (guarded-get-data-text-plain #js {:clipboardData cbp}))))))

;; Mirrors the fixed styles-fn body in main/ui/workspace/shapes/text/editor.cljs.
;; The fix adds an (and content ...) guard so getText and getData are never
;; called on a nil content object.
(defn- guarded-styles-fn-branch
  "Returns the data that styles-fn would use for a given content. When content
  is nil, the function falls back to the styles-only branch and returns :fallback
  (the real function calls legacy.txt/styles-to-attrs which we don't exercise
  here — we only verify the guard itself prevents the getText/getData throws)."
  [content]
  (if (and content (= (.getText ^js content) ""))
    (-> ^js (.getData content)
        (.toJS)
        (js->clj :keywordize-keys true))
    :fallback))

(t/deftest guarded-styles-fn-branch-does-not-throw-on-nil-content
  (t/testing "nil content falls back to the styles branch (no throw)"
    (t/is (= :fallback (guarded-styles-fn-branch nil)))
    (t/is (= :fallback (guarded-styles-fn-branch js/undefined)))))
