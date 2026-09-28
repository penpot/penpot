;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.history-test
  (:require
   [app.main.ui.workspace.sidebar.history :as history]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

;; entry-type->message resolves every undo entry type through a static
;; (tr "literal") call (see :penpot/tr-dynamic). The stub marks keys that
;; go through tr, so the test locks the exact key per type and proves
;; unknown types fall back to the raw key without translation.

(def ^:private multiple-attributes
  ["circle" "color" "component" "curve" "frame" "group" "media" "multiple"
   "page" "path" "rect" "shape" "text" "typography"])

(def ^:private single-attributes
  ["circle" "color" "component" "curve" "frame" "group" "image" "media"
   "multiple" "page" "path" "rect" "shape" "text" "typography"])

(t/deftest entry-type-message-uses-static-keys
  (with-redefs [i18n/tr (mock/stub (fn [k] (str "TR:" k)))]
    (t/testing "multiple arity resolves a literal key per attribute"
      (doseq [attribute multiple-attributes]
        (t/is (= (str "TR:workspace.undo.entry.multiple." attribute)
                 (history/entry-type->message (keyword attribute) true))
              attribute)))

    (t/testing "single arity resolves a literal key per attribute"
      (doseq [attribute single-attributes]
        (t/is (= (str "TR:workspace.undo.entry.single." attribute)
                 (history/entry-type->message (keyword attribute) false))
              attribute)))

    (t/testing "nil type reads as multiple"
      (t/is (= "TR:workspace.undo.entry.single.multiple"
               (history/entry-type->message nil false))))

    (t/testing "unknown attributes fall back to the raw key"
      (t/is (= "workspace.undo.entry.single.frobnicate"
               (history/entry-type->message :frobnicate false)))
      (t/is (= "workspace.undo.entry.multiple.frobnicate"
               (history/entry-type->message :frobnicate true))))))
