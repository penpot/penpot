;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-flex-layout-reflow-test
  "A flex reflow gives the same numbers on the JVM and in JS."
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Setup

(def ^:private flex-defaults
  {:layout :flex
   :layout-flex-dir :row
   :layout-padding-type :simple
   :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
   :layout-gap-type :multiple
   :layout-gap {:row-gap 0 :column-gap 0}
   :layout-align-items :start
   :layout-align-content :stretch
   :layout-justify-items :start
   :layout-justify-content :start
   :layout-wrap-type :nowrap
   :width 100
   :height 50})

(defn- add-flex
  [file label & {:as params}]
  (tho/add-frame file label (merge flex-defaults params)))

(defn- add-rect
  [file label parent-label width height & {:as params}]
  (tho/add-rect file label (merge {:parent-label parent-label :width width :height height} params)))

(defn- reflow
  "The objects of the page after the layout of the frame `label`."
  [file label]
  (let [objects    (:objects (thf/current-page file))
        modif-tree (gm/set-objects-modifiers {(thi/id label) {:modifiers (ctm/reflow-modifiers)}} objects)]
    (gsh/apply-objects-modifiers objects modif-tree)))

(defn- rect-of
  "[x y width height] of the shape `label`."
  [objects label]
  (let [{:keys [x y width height]} (get objects (thi/id label))]
    (mapv #(mth/precision % 2) [x y width height])))

;; ---- Tests

(t/deftest row-places-children-with-padding-and-gap
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :row :width 200 :layout-padding {:p1 10 :p2 10 :p3 10 :p4 10}
                              :layout-gap {:row-gap 0 :column-gap 5})
                    (add-rect :a :row 20 20)
                    (add-rect :b :row 30 20))
        objects (reflow file :row)]
    ;; A row lays out its children from the last one in `:shapes`
    (t/is (= [10.0 10.0 30.0 20.0] (rect-of objects :b)))
    (t/is (= [45.0 10.0 20.0 20.0] (rect-of objects :a)))))

(t/deftest column-hug-sizes-the-board
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :col :layout-flex-dir :column :layout-item-h-sizing :auto
                              :layout-item-v-sizing :auto
                              :layout-padding {:p1 5 :p2 5 :p3 5 :p4 5}
                              :layout-gap {:row-gap 3 :column-gap 0})
                    (add-rect :a :col 20 10)
                    (add-rect :b :col 30 10))
        objects (reflow file :col)]
    (t/is (= [0.0 0.0 40.0 33.0] (rect-of objects :col)))
    (t/is (= [5.0 5.0 30.0 10.0] (rect-of objects :b)))
    (t/is (= [5.0 18.0 20.0 10.0] (rect-of objects :a)))))

(t/deftest fill-child-takes-the-remaining-space
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :row :layout-gap {:row-gap 0 :column-gap 10})
                    (add-rect :a :row 20 20)
                    (add-rect :b :row 10 20 :layout-item-h-sizing :fill))
        objects (reflow file :row)]
    (t/is (= [0.0 0.0 70.0 20.0] (rect-of objects :b)))
    (t/is (= [80.0 0.0 20.0 20.0] (rect-of objects :a)))))

(t/deftest space-between-spreads-the-children
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :row :layout-justify-content :space-between)
                    (add-rect :a :row 20 20)
                    (add-rect :b :row 20 20)
                    (add-rect :c :row 20 20))
        objects (reflow file :row)]
    (t/is (= [80.0 40.0 0.0] (mapv #(first (rect-of objects %)) [:a :b :c])))))

(t/deftest space-between-keeps-the-base-gap-with-one-child
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :row :layout-justify-content :space-between
                              :layout-gap {:row-gap 0 :column-gap 5})
                    (add-rect :a :row 20 20))
        objects (reflow file :row)]
    (t/is (= [0.0 0.0 20.0 20.0] (rect-of objects :a)))))

(t/deftest space-between-keeps-the-base-gap-with-one-child-in-a-column
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :column :layout-flex-dir :column
                              :layout-justify-content :space-between
                              :layout-gap {:row-gap 5 :column-gap 0})
                    (add-rect :a :column 20 20))
        objects (reflow file :column)]
    (t/is (= [0.0 0.0 20.0 20.0] (rect-of objects :a)))))

(t/deftest space-around-and-evenly-spread-the-children
  (let [around  (-> (thf/sample-file :file1)
                    (add-flex :row :layout-justify-content :space-around)
                    (add-rect :a :row 20 20)
                    (add-rect :b :row 20 20)
                    (reflow :row))
        evenly  (-> (thf/sample-file :file2)
                    (add-flex :row2 :layout-justify-content :space-evenly)
                    (add-rect :c :row2 20 20)
                    (add-rect :d :row2 20 20)
                    (reflow :row2))]
    (t/is (= [65.0 15.0] (mapv #(first (rect-of around %)) [:a :b])))
    (t/is (= [60.0 20.0] (mapv #(first (rect-of evenly %)) [:c :d])))))

(t/deftest wrap-moves-the-overflow-to-a-new-line
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :row :width 50 :layout-wrap-type :wrap
                              :layout-gap {:row-gap 4 :column-gap 0})
                    (add-rect :a :row 30 10)
                    (add-rect :b :row 30 10))
        objects (reflow file :row)]
    ;; The two lines share the 50 px of height, 4 px apart
    (t/is (= [0.0 0.0] (subvec (rect-of objects :b) 0 2)))
    (t/is (= [0.0 27.0] (subvec (rect-of objects :a) 0 2)))))

(t/deftest nested-fill-boards-share-the-row
  ;; Issue 13353: a fixed row holds an icon and a fill board that holds a
  ;; fill rect. The row is 67 wide, 8 px of padding, 4 px of gap, 16 px icon.
  (let [file    (-> (thf/sample-file :file1)
                    (add-flex :row :width 67 :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}
                              :layout-gap {:row-gap 0 :column-gap 4})
                    (add-flex :board :parent-label :row :layout-flex-dir :column
                              :layout-item-h-sizing :fill :width 60
                              :layout-padding {:p1 0 :p2 2 :p3 0 :p4 2})
                    (add-rect :inner :board 10 16 :layout-item-h-sizing :fill)
                    (add-rect :icon :row 16 16))
        objects (reflow file :row)]
    (t/is (= 31.0 (nth (rect-of objects :board) 2)))
    (t/is (= 27.0 (nth (rect-of objects :inner) 2)))
    (t/is (= 28.0 (first (rect-of objects :board))))
    (t/is (= 8.0 (first (rect-of objects :icon))))))
