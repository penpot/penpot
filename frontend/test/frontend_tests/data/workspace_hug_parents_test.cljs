;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-hug-parents-test
  "A hug board that loses the child that sized it must stop hugging, or its
  fill children collapse to the minimum size."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- setup-file
  "A column board hugging its width, with a fixed rect and a fill rect.
  `extra?` adds a second fixed rect, so the board keeps a sizing child."
  [extra?]
  (cond-> (-> (cthf/sample-file :file1)
              (ctho/add-frame :frame
                              :x 0 :y 0 :width 80 :height 200
                              :layout :flex
                              :layout-flex-dir :column
                              :layout-item-h-sizing :auto
                              :layout-item-v-sizing :fix)
              (cths/add-sample-shape :fixed
                                     :type :rect
                                     :parent-label :frame
                                     :width 80 :height 20
                                     :layout-item-h-sizing :fix
                                     :layout-item-v-sizing :fix)
              (cths/add-sample-shape :filled
                                     :type :rect
                                     :parent-label :frame
                                     :width 80 :height 20
                                     :layout-item-h-sizing :fill
                                     :layout-item-v-sizing :fix))
    extra?
    (cths/add-sample-shape :other
                           :type :rect
                           :parent-label :frame
                           :width 80 :height 20
                           :layout-item-h-sizing :fix
                           :layout-item-v-sizing :fix)))

(defn- run
  "Runs `events` on a store holding `file` and calls `check` with the file
  read back from the final state."
  [file events check]
  (t/async
    done
    (let [store (ths/setup-store file)]
      (ths/run-store store done events
                     (fn [state]
                       (check (ths/get-file-from-state state)))))))

(defn- hug-stopped?
  [file']
  (and (= :fix (:layout-item-h-sizing (cths/get-shape file' :frame)))
       (= :fill (:layout-item-h-sizing (cths/get-shape file' :filled)))))

(t/deftest delete-the-sizing-child-fixes-the-hug-board
  (run (setup-file false)
       [(dwsh/delete-shapes #{(cthi/id :fixed)})]
       (fn [file']
         (t/is (hug-stopped? file')))))

(t/deftest delete-keeps-the-hug-while-another-child-sizes-the-board
  (run (setup-file true)
       [(dwsh/delete-shapes #{(cthi/id :fixed)})]
       (fn [file']
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest delete-the-only-child-keeps-the-hug
  (run (-> (cthf/sample-file :file1)
           (ctho/add-frame :frame
                           :x 0 :y 0 :width 80 :height 200
                           :layout :flex
                           :layout-flex-dir :column
                           :layout-item-h-sizing :auto)
           (cths/add-sample-shape :only
                                  :type :rect
                                  :parent-label :frame
                                  :layout-item-h-sizing :fix))
       [(dwsh/delete-shapes #{(cthi/id :only)})]
       (fn [file']
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest delete-is-undone-with-the-sizing-change
  (run (setup-file false)
       [(tohs/watch-undo-stack)
        (dwsh/delete-shapes #{(cthi/id :fixed)})
        dwu/undo]
       (fn [file']
         (t/is (some? (cths/get-shape file' :fixed)))
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest hide-the-sizing-child-fixes-the-hug-board
  (run (setup-file false)
       [(dwsh/update-shape-flags [(cthi/id :fixed)] {:hidden true})]
       (fn [file']
         (t/is (hug-stopped? file')))))

(t/deftest hide-is-undone-with-the-sizing-change
  (run (setup-file false)
       [(tohs/watch-undo-stack)
        (dwsh/update-shape-flags [(cthi/id :fixed)] {:hidden true})
        dwu/undo]
       (fn [file']
         (t/is (not (:hidden (cths/get-shape file' :fixed))))
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest lock-only-keeps-the-hug
  (run (setup-file false)
       [(dwsh/update-shape-flags [(cthi/id :fixed)] {:blocked true})]
       (fn [file']
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest toggle-visibility-of-the-sizing-child-fixes-the-hug-board
  (run (setup-file false)
       [(dws/select-shape (cthi/id :fixed))
        (dwsh/toggle-visibility-selected)]
       (fn [file']
         (t/is (hug-stopped? file')))))

(t/deftest toggle-visibility-is-undone-with-the-sizing-change
  (run (setup-file false)
       [(tohs/watch-undo-stack)
        (dws/select-shape (cthi/id :fixed))
        (dwsh/toggle-visibility-selected)
        dwu/undo]
       (fn [file']
         (t/is (not (:hidden (cths/get-shape file' :fixed))))
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest plugin-hidden-setter-fixes-the-hug-board
  (thw/with-wasm-mocks*
    (fn []
      (let [file        (setup-file false)
            store       (ths/setup-store file)
            ^js context (api/create-context plugin-id)
            _           (set! st/state store)
            ^js page    (.-currentPage context)
            ^js shape   (.getShapeById page (str (cthi/id :fixed)))]
        (set! (.-hidden shape) true)
        (t/is (hug-stopped? (ths/get-file-from-state @store)))))))

;; Showing a hidden fill child

(t/deftest show-a-fill-child-of-an-empty-hug-board-fixes-the-board
  (run (setup-file false)
       [(dwsh/update-shape-flags [(cthi/id :filled)] {:hidden true})
        (dwsh/update-shape-flags [(cthi/id :fixed)] {:hidden true})
        (dwsh/update-shape-flags [(cthi/id :filled)] {:hidden false})]
       (fn [file']
         (t/is (hug-stopped? file')))))

(t/deftest toggle-visibility-shows-a-fill-child-of-an-empty-hug-board-fixed
  (run (setup-file false)
       [(dwsh/update-shape-flags [(cthi/id :filled)] {:hidden true})
        (dwsh/update-shape-flags [(cthi/id :fixed)] {:hidden true})
        (dws/select-shape (cthi/id :filled))
        (dwsh/toggle-visibility-selected)]
       (fn [file']
         (t/is (hug-stopped? file')))))

(t/deftest show-is-undone-with-the-sizing-change
  (run (setup-file false)
       [(tohs/watch-undo-stack)
        (dwsh/update-shape-flags [(cthi/id :filled)] {:hidden true})
        (dwsh/update-shape-flags [(cthi/id :fixed)] {:hidden true})
        (dwsh/update-shape-flags [(cthi/id :filled)] {:hidden false})
        dwu/undo]
       (fn [file']
         (t/is (true? (:hidden (cths/get-shape file' :filled))))
         (t/is (= :auto (:layout-item-h-sizing (cths/get-shape file' :frame)))))))

(t/deftest show-a-child-set-to-fill-while-hidden-fixes-the-hug-board
  (run (-> (cthf/sample-file :file1)
           (ctho/add-frame :frame
                           :x 0 :y 0 :width 80 :height 40
                           :layout :flex
                           :layout-flex-dir :row
                           :layout-item-h-sizing :auto
                           :layout-item-v-sizing :fix)
           (cths/add-sample-shape :visible
                                  :type :rect
                                  :parent-label :frame
                                  :width 40 :height 40
                                  :layout-item-h-sizing :fix
                                  :layout-item-v-sizing :fix)
           (cths/add-sample-shape :hidden
                                  :type :rect
                                  :parent-label :frame
                                  :width 40 :height 40
                                  :hidden true
                                  :layout-item-h-sizing :fix
                                  :layout-item-v-sizing :fix))
       [(dwsl/update-layout-child [(cthi/id :hidden)] {:layout-item-h-sizing :fill})
        (dwsh/update-shape-flags [(cthi/id :hidden)] {:hidden false})]
       (fn [file']
         (t/is (= :fix (:layout-item-h-sizing (cths/get-shape file' :frame))))
         (t/is (= :fill (:layout-item-h-sizing (cths/get-shape file' :hidden)))))))

(t/deftest plugin-visible-setter-fixes-the-hug-board
  (thw/with-wasm-mocks*
    (fn []
      (let [file        (setup-file false)
            store       (ths/setup-store file)
            ^js context (api/create-context plugin-id)
            _           (set! st/state store)
            ^js page    (.-currentPage context)
            ^js shape   (.getShapeById page (str (cthi/id :fixed)))]
        (set! (.-visible shape) false)
        (t/is (hug-stopped? (ths/get-file-from-state @store)))))))

;; Deleting more than the direct child

(t/deftest delete-a-grouped-child-fixes-the-hug-board
  (run (-> (cthf/sample-file :file1)
           (ctho/add-frame :frame
                           :x 0 :y 0 :width 80 :height 200
                           :layout :flex
                           :layout-flex-dir :column
                           :layout-item-h-sizing :auto
                           :layout-item-v-sizing :fix)
           (ctho/add-group :group :parent-label :frame)
           (cths/add-sample-shape :grouped
                                  :type :rect
                                  :parent-label :group
                                  :width 80 :height 20)
           (cths/add-sample-shape :filled
                                  :type :rect
                                  :parent-label :frame
                                  :width 80 :height 20
                                  :layout-item-h-sizing :fill
                                  :layout-item-v-sizing :fix))
       [(dwsh/delete-shapes #{(cthi/id :grouped)})]
       (fn [file']
         (t/is (nil? (cths/get-shape file' :group)))
         (t/is (hug-stopped? file')))))

(t/deftest delete-on-another-page-fixes-the-hug-board
  (let [file (-> (cthf/sample-file :file1 :page-label :page-a)
                 (cthf/add-sample-page :page-b)
                 (cthf/switch-to-page :page-b)
                 (ctho/add-frame :frame
                                 :x 0 :y 0 :width 80 :height 200
                                 :layout :flex
                                 :layout-flex-dir :column
                                 :layout-item-h-sizing :auto
                                 :layout-item-v-sizing :fix)
                 (cths/add-sample-shape :fixed
                                        :type :rect
                                        :parent-label :frame
                                        :width 80 :height 20
                                        :layout-item-h-sizing :fix
                                        :layout-item-v-sizing :fix)
                 (cths/add-sample-shape :filled
                                        :type :rect
                                        :parent-label :frame
                                        :width 80 :height 20
                                        :layout-item-h-sizing :fill
                                        :layout-item-v-sizing :fix)
                 (cthf/switch-to-page :page-a))]
    (run file
         [(dwsh/delete-shapes (cthi/id :page-b) #{(cthi/id :fixed)})]
         (fn [file']
           (t/is (= :fix (:layout-item-h-sizing (cths/get-shape file' :frame :page-label :page-b))))
           (t/is (= :fill (:layout-item-h-sizing (cths/get-shape file' :filled :page-label :page-b))))))))
