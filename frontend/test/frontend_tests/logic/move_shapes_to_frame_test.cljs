;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.move-shapes-to-frame-test
  "Tests for the reparenting step that ends a move (`dwt/move-shapes-to-frame`)."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.transforms :as dwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- count-commits
  "Runs `event` on a store of `file` and calls `check` with the number of
  commits it made and the resulting file."
  [done file event check]
  (let [store   (ths/setup-store file)
        commits (atom 0)
        sub     (->> (ptk/input-stream store)
                     (rx/filter dch/commit?)
                     (rx/subs! (fn [_] (swap! commits inc))))]
    (ths/run-store store done [event]
                   (fn [state]
                     (rx/dispose! sub)
                     (check @commits (ths/get-file-from-state state))))))

;; Scenario: a move ends over the frame the shape already belongs to.
;; Proves: nothing changes parent, so no commit (and no save) is made.
(t/deftest moving-a-shape-within-its-frame-commits-nothing
  (t/async
    done
    (let [file (-> (cthf/sample-file :file1)
                   (ctho/add-rect :rect1))
          rect (cths/get-shape file :rect1)]
      (count-commits done file
                     (dwt/move-shapes-to-frame [(:id rect)] uuid/zero nil nil)
                     (fn [commits _]
                       (t/is (zero? commits)))))))

;; Scenario: a move ends over another board. Proves: the shape is moved into
;; it with one commit.
(t/deftest moving-a-shape-into-a-board-commits-the-new-parent
  (t/async
    done
    (let [file  (-> (cthf/sample-file :file1)
                    (ctho/add-frame :frame1)
                    (ctho/add-rect :rect1))
          rect  (cths/get-shape file :rect1)
          frame (cths/get-shape file :frame1)]
      (count-commits done file
                     (dwt/move-shapes-to-frame [(:id rect)] (:id frame) nil nil)
                     (fn [commits file']
                       (t/is (= 1 commits))
                       (t/is (= (:id frame) (:parent-id (cths/get-shape file' :rect1)))))))))
