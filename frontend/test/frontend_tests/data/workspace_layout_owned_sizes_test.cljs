;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-layout-owned-sizes-test
  "The size of a board that hugs its content follows the layout: a resize
  of a child in a copy touches the child, not the board."
  (:require
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.main.data.workspace.transforms :as dwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(t/use-fixtures :each ftm/fixture)

(defn- hug-component-with-copy
  "A component whose root has a flex layout (row) and hugs its 50x50
   child, and a copy of it."
  []
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy
                  :component1 :main-root :main-child :copy-root
                  :main-root-params {:layout :flex :layout-flex-dir :row
                                     :layout-item-h-sizing :auto
                                     :layout-item-v-sizing :auto
                                     :width 50 :height 50}
                  :main-child-params {:width 50 :height 50}
                  :copy-root-params {:children-labels [:copy-child]}))]
    (tm/make-situation file)))

(t/deftest ^:async a-resize-of-a-copy-child-leaves-its-hugging-board-untouched
  (ftm/install! (hug-component-with-copy))
  (await (ftm/await-step [(dwt/update-dimensions [(thi/id :copy-child)] :width 80)]))
  (let [file  (ftm/current-file)
        child (ths/get-shape file :copy-child)
        root  (ths/get-shape file :copy-root)]
    (t/is (= 80 (:width child)))
    (t/is (= 80 (:width root)))
    (t/is (contains? (:touched child) :geometry-group))
    (t/is (not (contains? (:touched root) :geometry-group)))))
