;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.combobox-navigation-test
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.management.forms.controls.combobox-navigation :as cbn]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as cu]
   [cljs.test :as t :include-macros true]))

(def ^:private tokens
  (map #(ctob/make-token :name % :type :spacing :value "4")
       ["spacing.lg" "spacing.md" "spacing.sm"]))

(def ^:private options
  (cu/focusable-options @(cu/get-token-dropdown-options {:spacing tokens} "")))

(defn- option-id
  [token-name]
  (some #(when (= token-name (:name %)) (:id %)) options))

(defn- next-name
  [focused-name direction]
  (let [next-id (cbn/next-focus-id options (option-id focused-name) direction)]
    (some #(when (= next-id (:id %)) (:name %)) options)))

(t/deftest next-focus-id
  (t/testing "moves to the next and previous option"
    (t/is (= "spacing.md" (next-name "spacing.lg" :down)))
    (t/is (= "spacing.lg" (next-name "spacing.md" :up))))

  (t/testing "wraps around at either end"
    (t/is (= "spacing.lg" (next-name "spacing.sm" :down)))
    (t/is (= "spacing.sm" (next-name "spacing.lg" :up))))

  (t/testing "without a focused option both directions start at the first one"
    (t/is (= "spacing.lg" (next-name nil :down)))
    (t/is (= "spacing.lg" (next-name nil :up))))

  (t/testing "without options nothing gets focus"
    (t/is (nil? (cbn/next-focus-id [] nil :down)))
    (t/is (nil? (cbn/next-focus-id [] nil :up)))))
