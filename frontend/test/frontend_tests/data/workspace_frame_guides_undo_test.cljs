;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-frame-guides-undo-test
  "Undo of a transform of a main board that holds a guide: the transform
  moves the guide in a commit of its own before the shapes, and the copies'
  sync must still be undone with the transform."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(t/use-fixtures :each ftm/fixture)

(defn- add-guide
  "The commit, out of the undo stack, of a guide on the board `label`."
  [label]
  (let [page (thf/current-page (ftm/current-file))
        id   (uuid/next)]
    (dch/commit-changes
     (-> (pcb/empty-changes nil (:id page))
         (pcb/with-page page)
         (pcb/set-guide id {:id       id
                            :axis     :x
                            :position 10
                            :frame-id (thi/id label)})
         (pcb/set-save-undo? false)))))

(defn- rotation-of
  [label]
  (:rotation (ths/get-shape (ftm/current-file) label)))

(defn- guide-positions
  []
  (mapv :position (vals (:guides (thf/current-page (ftm/current-file))))))

(t/deftest ^:async one-undo-reverts-a-rotation-of-a-main-with-a-guide
  ;; the guide does not move, and adds no commit of its own
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (await (ftm/await-step [(add-guide :main-root)]))
  (await (ftm/await-step [(dwt/increase-rotation [(thi/id :main-root)] 45)]))
  (t/is (= 45 (rotation-of :main-root)))
  (t/is (= 45 (rotation-of :copy-root)))

  (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
  (t/is (= 0 (rotation-of :main-root)))
  (t/is (= 0 (rotation-of :copy-root))))

(t/deftest ^:async one-undo-reverts-a-move-of-a-main-with-its-guide
  (ftm/install! (setup/simple-component-with-labeled-copy))
  (await (ftm/await-step [(add-guide :main-root)]))
  (let [x (:x (ths/get-shape (ftm/current-file) :main-root))]
    (await (ftm/await-step [(dwt/update-position (thi/id :main-root) {:x (+ x 50)})]))
    (t/is (= [60] (guide-positions)))

    (t/is (= :settled (await (ftm/await-step [dwu/undo]))))
    (t/is (= x (:x (ths/get-shape (ftm/current-file) :main-root))))
    (t/is (= [10] (guide-positions)))))
