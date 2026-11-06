;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-group-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.ui.workspace.tokens.management.group :as wtg]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private type-unfolded? @#'wtg/type-unfolded?)
(def ^:private editing-text? @#'wtg/editing-text?)
(def ^:private pill-click-action @#'wtg/pill-click-action)
(def ^:private unfolded-token-types @#'dwtl/make-unfolded-token-types-state)

(def ^:private radius
  (ctob/make-token :name "radius.md" :type :border-radius :value "8"))

(def ^:private number
  (ctob/make-token :name "scale.md" :type :number :value "2"))

(t/deftest section-unfolded
  (let [unfolded (unfolded-token-types (thi/new-id! :file1) (thi/new-id! :set1) #{:color})]
    (t/testing "open in the file and set it was opened in"
      (t/is (type-unfolded? unfolded (thi/id :file1) (thi/id :set1) :color)))

    (t/testing "closed in another set or file"
      (t/is (not (type-unfolded? unfolded (thi/id :file1) (thi/new-id! :set2) :color)))
      (t/is (not (type-unfolded? unfolded (thi/new-id! :file2) (thi/id :set1) :color))))

    (t/testing "other types stay closed"
      (t/is (not (type-unfolded? unfolded (thi/id :file1) (thi/id :set1) :spacing))))))

(t/deftest text-edition
  (let [text    (ths/sample-shape :text1 :type :text)
        rect    (ths/sample-shape :rect1)
        objects {(:id text) text (:id rect) rect}]
    (t/testing "nothing is being edited"
      (t/is (not (editing-text? nil nil objects))))

    (t/testing "a text is being edited"
      (t/is (editing-text? nil (:id text) objects))
      (t/is (editing-text? {(:id text) :editor-state} nil objects)))

    (t/testing "editing another shape isn't text edition"
      (t/is (not (editing-text? nil (:id rect) objects))))))

(t/deftest pill-click
  (let [selection [(ths/sample-shape :rect1)]]
    (t/testing "applies or removes the token on the selection"
      (t/is (= :toggle (pill-click-action radius selection false))))

    (t/testing "warns while a text is being edited"
      (t/is (= :warn-text-edition (pill-click-action radius selection true))))

    (t/testing "does nothing without a selection"
      (t/is (nil? (pill-click-action radius [] false)))
      (t/is (nil? (pill-click-action radius [] true))))

    (t/testing "number tokens can't be applied with a click"
      (t/is (nil? (pill-click-action number selection false))))))
