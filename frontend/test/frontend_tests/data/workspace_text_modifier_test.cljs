;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-text-modifier-test
  "The size the SVG renderer measures for a text while it is edited, which
  the edit stores when it ends: it follows the content, so it touches
  nothing."
  (:require
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as tht]
   [app.main.data.workspace.texts :as dwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(t/use-fixtures :each ftm/fixture)

(defn- measure
  "Stands in for the DOM measurement of the text `id`."
  [id size]
  (fn [state]
    (assoc-in state [:workspace-text-modifier id] size)))

(t/deftest ^:async an-edit-of-a-copy-text-touches-its-content-only
  (ftm/install! (setup/simple-text-component-with-copy))
  (let [id (thi/id :copy-text)]
    (await (ftm/await-step [(measure id {:width 140})
                            (dwt/v2-update-text-shape-content id (tht/content [[["hello there"]]]))]))
    (let [text (ths/get-shape (ftm/current-file) :copy-text)]
      (t/is (= [[["hello there"]]] (tht/text-of (ftm/current-file) :copy-text)))
      (t/is (= 140 (:width text)))
      (t/is (contains? (:touched text) :content-group))
      (t/is (not (contains? (:touched text) :geometry-group))))))
