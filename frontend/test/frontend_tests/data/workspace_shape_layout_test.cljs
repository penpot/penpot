;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-shape-layout-test
  (:require
   [app.common.schema :as sm]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(def ^:private valid-layout-padding?
  (sm/lazy-validator ctl/schema:layout-attrs))

(t/deftest update-shape-layout-patches-one-padding-side
  (let [shape  {:layout :flex
                :layout-padding-type :multiple
                :layout-padding {:p1 1 :p2 2 :p3 3 :p4 4}}
        result (dwsl/update-shape-layout shape {:layout-padding {:p1 12}})]
    (t/is (= {:p1 12 :p2 2 :p3 3 :p4 4} (:layout-padding result)))))

(t/deftest update-shape-layout-completes-a-missing-padding
  ;; Regression for https://github.com/penpot/penpot/issues/12463.
  ;; A layout without padding (made through the API) saved `{:p1 12}`
  ;; after setting one side, and the backend rejected the shape.
  (let [shape  {:layout :flex :layout-padding-type :multiple}
        result (dwsl/update-shape-layout shape {:layout-padding {:p1 12}})]
    (t/is (= {:p1 12 :p2 0 :p3 0 :p4 0} (:layout-padding result)))
    (t/is (valid-layout-padding? (select-keys result [:layout-padding])))))

(t/deftest update-shape-layout-leaves-padding-alone-for-other-changes
  (let [shape  {:layout :flex}
        result (dwsl/update-shape-layout shape {:layout-gap {:row-gap 4}})]
    (t/is (not (contains? result :layout-padding)))
    (t/is (= {:row-gap 4} (:layout-gap result)))))

(t/deftest update-shape-layout-completes-a-padding-side-cleared-to-nil
  (let [shape  {:layout :flex
                :layout-padding-type :multiple
                :layout-padding {:p1 1 :p2 2 :p3 3 :p4 4}}
        result (dwsl/update-shape-layout shape {:layout-padding {:p1 nil}})]
    (t/is (= {:p1 0 :p2 2 :p3 3 :p4 4} (:layout-padding result)))
    (t/is (valid-layout-padding? (select-keys result [:layout-padding])))))

(t/deftest update-shape-layout-removes-padding-when-the-whole-value-is-nil
  (let [shape  {:layout :flex
                :layout-padding-type :multiple
                :layout-padding {:p1 1 :p2 2 :p3 3 :p4 4}}
        result (dwsl/update-shape-layout shape {:layout-padding nil})]
    (t/is (not (contains? result :layout-padding)))))

(t/deftest update-shape-layout-completes-a-stored-partial-padding
  ;; A board saved with a partial padding before the fix is completed by
  ;; any later layout change, not only by a padding one.
  (let [shape  {:layout :flex
                :layout-padding-type :multiple
                :layout-padding {:p1 12}}
        result (dwsl/update-shape-layout shape {:layout-gap {:row-gap 4}})]
    (t/is (= {:p1 12 :p2 0 :p3 0 :p4 0} (:layout-padding result)))
    (t/is (valid-layout-padding? (select-keys result [:layout-padding])))))

(defn- setup-nested-fill-file
  "A row board with a fill column board that holds a fill rect."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :frame
                      :x 0 :y 0 :width 100 :height 40
                      :layout :flex
                      :layout-flex-dir :row)
      (ctho/add-frame :inner
                      :parent-label :frame
                      :width 60 :height 40
                      :layout :flex
                      :layout-flex-dir :column
                      :layout-item-h-sizing :fill
                      :layout-item-v-sizing :fix)
      (cths/add-sample-shape :child
                             :type :rect
                             :parent-label :inner
                             :width 60 :height 16
                             :layout-item-h-sizing :fill
                             :layout-item-v-sizing :fix)))

(t/deftest update-layout-child-hug-fixes-only-direct-fill-children
  (t/async
    done
    (let [file  (setup-nested-fill-file)
          store (ths/setup-store file)
          event (dwsl/update-layout-child [(cthi/id :frame)]
                                          {:layout-item-h-sizing :auto})]
      (ths/run-store
       store done [event]
       (fn [new-state]
         (let [file' (ths/get-file-from-state new-state)
               inner (cths/get-shape file' :inner)
               child (cths/get-shape file' :child)]
           (t/is (= :fix (:layout-item-h-sizing inner))
                 "the fill direct child of the hug board becomes fix")
           (t/is (= :fill (:layout-item-h-sizing child))
                 "the fill grandchild keeps its sizing")))))))

(defn- setup-hug-column-file
  "A column board hugging its width, with a fixed rect and a fill rect.
  `extra?` adds a second fixed rect."
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

(t/deftest update-layout-child-absolute-fixes-a-hug-board-left-with-fill-children
  (t/async
    done
    (let [file  (setup-hug-column-file false)
          store (ths/setup-store file)
          event (dwsl/update-layout-child [(cthi/id :fixed)]
                                          {:layout-item-absolute true})]
      (ths/run-store
       store done [event]
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               frame   (cths/get-shape file' :frame)
               filled  (cths/get-shape file' :filled)]
           (t/is (= :fix (:layout-item-h-sizing frame))
                 "the board stops hugging")
           (t/is (= :fill (:layout-item-h-sizing filled))
                 "the remaining child keeps its fill")))))))

(t/deftest update-layout-child-absolute-keeps-hug-when-another-child-sizes-the-board
  (t/async
    done
    (let [file  (setup-hug-column-file true)
          store (ths/setup-store file)
          event (dwsl/update-layout-child [(cthi/id :fixed)]
                                          {:layout-item-absolute true})]
      (ths/run-store
       store done [event]
       (fn [new-state]
         (let [file'  (ths/get-file-from-state new-state)
               frame  (cths/get-shape file' :frame)]
           (t/is (= :auto (:layout-item-h-sizing frame)))))))))

(defn- setup-hug-grid-file
  "A grid board hugging its width, with one fix child. `extra-attrs` are
  merged into the board."
  [extra-attrs]
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :frame
                      (merge {:x 0 :y 0 :width 80 :height 80
                              :layout :grid
                              :layout-grid-dir :row
                              :layout-grid-rows [{:type :flex :value 1}]
                              :layout-grid-columns [{:type :flex :value 1}]
                              :layout-grid-cells {}
                              :layout-item-h-sizing :auto
                              :layout-item-v-sizing :fix}
                             extra-attrs))
      (cths/add-sample-shape :child
                             :type :rect
                             :parent-label :frame
                             :width 80 :height 80
                             :layout-item-h-sizing :fix
                             :layout-item-v-sizing :fix)))

(defn- check-grid-child-fill
  [file]
  (t/async
    done
    (let [store (ths/setup-store file)
          event (dwsl/update-layout-child [(cthi/id :child)]
                                          {:layout-item-h-sizing :fill})]
      (ths/run-store
       store done [event]
       (fn [new-state]
         (let [file' (ths/get-file-from-state new-state)]
           (t/is (= :fix (:layout-item-h-sizing (cths/get-shape file' :frame)))
                 "the grid stops hugging")
           (t/is (= :fill (:layout-item-h-sizing (cths/get-shape file' :child)))
                 "the child keeps its fill")))))))

(t/deftest update-layout-child-fill-fixes-a-hug-grid
  (check-grid-child-fill (setup-hug-grid-file {})))

(t/deftest update-layout-child-fill-fixes-a-hug-grid-with-leftover-flex-direction
  (check-grid-child-fill (setup-hug-grid-file {:layout-flex-dir :column})))
