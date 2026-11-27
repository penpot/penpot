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
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.modifiers :as dwm]
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

(defn- hug-row-file
  "A row board hugging its width at the width of its only child, and a fill
  rect outside of it."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :hug
                      :x 0 :y 0 :width 32 :height 40
                      :layout :flex
                      :layout-flex-dir :row
                      :layout-item-h-sizing :auto
                      :layout-item-v-sizing :fix)
      (cths/add-sample-shape :inside
                             :type :rect
                             :parent-label :hug
                             :width 32 :height 40
                             :layout-item-h-sizing :fix
                             :layout-item-v-sizing :fix)
      (cths/add-sample-shape :arriving
                             :type :rect
                             :x 300 :y 0 :width 32 :height 16
                             :layout-item-h-sizing :fill
                             :layout-item-v-sizing :fix)))

(defn- hug-grid-file
  "A grid board hugging its width, with a fixed child and a fill sibling."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :hug
                      :x 0 :y 0 :width 64 :height 40
                      :layout :grid
                      :layout-grid-dir :row
                      :layout-grid-columns [{:type :flex :value 1}
                                            {:type :flex :value 1}]
                      :layout-grid-rows [{:type :flex :value 1}]
                      :layout-grid-cells {}
                      :layout-item-h-sizing :auto
                      :layout-item-v-sizing :fix)
      (cths/add-sample-shape :leaving
                             :type :rect
                             :parent-label :hug
                             :width 32 :height 40
                             :layout-item-h-sizing :fix
                             :layout-item-v-sizing :fix)
      (cths/add-sample-shape :staying
                             :type :rect
                             :parent-label :hug
                             :width 32 :height 40
                             :layout-item-h-sizing :fill
                             :layout-item-v-sizing :fix)))

;; Scenario: a move ends over a row board that hugs its width at the width of
;; its children. Proves: the arriving fill shape turns fix, and the board keeps
;; hugging.
(t/deftest moving-a-fill-shape-into-a-hug-board-fixes-the-shape
  (t/async
    done
    (count-commits done (hug-row-file)
                   (dwt/move-shapes-to-frame [(cthi/id :arriving)] (cthi/id :hug) nil nil)
                   (fn [_ file']
                     (t/is (= :fix (:layout-item-h-sizing (cths/get-shape file' :arriving))))
                     (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :hug))))))))

;; Scenario: the drag preview of the same move. Proves: it fixes the arriving
;; shape and leaves the board alone, as the commit does.
(t/deftest dragging-a-fill-shape-over-a-hug-board-previews-the-fixed-shape
  (let [file      (hug-row-file)
        objects   (-> (cthf/current-page file) :objects)
        arriving  (cthi/id :arriving)
        hug       (cthi/id :hug)
        modifiers (fn [tree id]
                    (->> (get-in tree [id :modifiers :structure-parent])
                         (filter #(= :change-property (:type %)))
                         (map (juxt :property :value))
                         set))
        tree      (-> (dwm/create-modif-tree [arriving] (ctm/empty))
                      (dwm/build-change-frame-modifiers objects [arriving] hug 0 nil))]
    (t/is (contains? (modifiers tree arriving) [:layout-item-h-sizing :fix]))
    (t/is (not-any? (comp #{:layout-item-h-sizing :layout-item-v-sizing} first)
                    (modifiers tree hug)))))

(t/deftest dragging-a-fixed-child-out-of-a-hug-grid-previews-a-fixed-parent
  (let [file      (hug-grid-file)
        objects   (-> (cthf/current-page file) :objects)
        leaving   (cthi/id :leaving)
        hug       (cthi/id :hug)
        modifiers (fn [tree id]
                    (->> (get-in tree [id :modifiers :structure-parent])
                         (filter #(= :change-property (:type %)))
                         (map (juxt :property :value))
                         set))
        tree      (-> (dwm/create-modif-tree [leaving] (ctm/empty))
                      (dwm/build-change-frame-modifiers objects [leaving] uuid/zero 0 nil))]
    (t/is (contains? (modifiers tree hug) [:layout-item-h-sizing :fix]))))
