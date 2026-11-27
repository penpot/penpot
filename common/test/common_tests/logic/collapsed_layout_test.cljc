;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.collapsed-layout-test
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.collapsed-layout :as clc]
   [app.common.math :as mth]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Setup

(defn- raw-update
  "Updates the shape directly, to build data the editor would not produce."
  [file label f]
  (let [page (thf/current-page file)]
    (update file :data
            (fn [fdata]
              (ctpl/update-page fdata (:id page)
                                #(ctst/set-shape % (f (ctst/get-shape % (thi/id label)))))))))

(defn- resized
  "Resizes in two steps: a modifier that shrinks a shape by a factor below
  1/1000 is ignored."
  [shape attr value]
  (let [resize #(gsh/transform-shape %1 (ctm/change-dimensions-modifiers %1 attr %2 {:ignore-lock? true}))]
    (cond-> shape
      (< value 1) (resize 1)
      :always     (resize value))))

(defn- collapse
  "Leaves the shape at `size` on the axis, with the given sizing and touched."
  [file label attr sizing size & {:keys [touched]}]
  (raw-update file label
              (fn [shape]
                (cond-> (-> (resized shape attr size)
                            (assoc (if (= attr :width) :layout-item-h-sizing :layout-item-v-sizing) sizing))
                  (some? touched) (assoc :touched touched)))))

(defn- set-sizing
  [file label & {:as attrs}]
  (raw-update file label #(merge % attrs)))

(defn- copy-of
  "Labels the copy shape that points at the main shape `main-label`."
  [file main-label copy-label]
  (let [main-id (thi/id main-label)
        copy    (d/seek #(= main-id (:shape-ref %)) (vals (:objects (thf/current-page file))))]
    (thi/set-id! copy-label (:id copy))
    file))

(defn- repair
  "The result of repairing the current page of `file`."
  ([file]
   (repair file {(:id file) file}))
  ([file libs]
   (clc/repair-page file (thf/current-page file) libs)))

(defn- shape
  [result label]
  (get (:objects result) (thi/id label)))

(defn- repaired?
  [result label]
  (contains? (:ids result) (thi/id label)))

(def ^:private flex-defaults
  {:layout :flex
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

(defn- row-frame
  [file label & {:as params}]
  (tho/add-frame file label (merge flex-defaults {:layout-flex-dir :row} params)))

(defn- column-frame
  [file label & {:as params}]
  (tho/add-frame file label (merge flex-defaults {:layout-flex-dir :column} params)))

(defn- add-text
  [file label parent-label & {:as params}]
  (tho/add-text file label "Input"
                :text-params (merge {:parent-label parent-label :grow-type :fixed :width 50 :height 16} params)))

(defn- issue-file
  "The data of issue 13353: a copy row (fix 67, padding 8, gap 4, 16 px icon)
  with a fill board (padding 2) that holds a fill text, both stored as :fix at
  the minimum and touched; the main has :fill."
  []
  (let [touched #{:geometry-group :layout-item-h-sizing}]
    (-> (thf/sample-file :file1)
        (row-frame :main-row :width 67 :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8}
                   :layout-gap {:row-gap 0 :column-gap 4}
                   :layout-item-h-sizing :auto)
        (column-frame :main-board :parent-label :main-row :layout-item-h-sizing :fill
                      :layout-padding {:p1 0 :p2 2 :p3 0 :p4 2})
        (add-text :main-text :main-board :layout-item-h-sizing :fill)
        (tho/add-rect :main-rect :parent-label :main-board :width 40 :height 20)
        (column-frame :main-inner :parent-label :main-board :width 60 :height 30)
        (tho/add-rect :main-icon :parent-label :main-row :width 16 :height 16)
        (thc/make-component :component1 :main-row)
        (row-frame :container :width 400)
        (thc/instantiate-component :component1 :copy-row
                                   :parent-label :container
                                   :children-labels [:c1 :c2 :c3 :c4 :c5])
        (copy-of :main-board :copy-board)
        (copy-of :main-text :copy-text)
        (copy-of :main-rect :copy-rect)
        (copy-of :main-inner :copy-inner)
        (set-sizing :copy-row :layout-item-h-sizing :fix)
        (raw-update :copy-row #(resized % :width 67))
        (collapse :copy-board :width :fix 0.01 :touched touched)
        (collapse :copy-text :width :fix 3.99 :touched (into touched [:content-group :text-content-attribute :text-content-text])))))

;; ---- Copies: restore the main sizing

(t/deftest restores-the-frozen-overrides-of-a-copy
  (let [result (repair (issue-file))
        board  (shape result :copy-board)
        text   (shape result :copy-text)]
    (t/is (= :fill (:layout-item-h-sizing board)))
    (t/is (= #{:geometry-group} (:touched board)))
    (t/is (= :fill (:layout-item-h-sizing text)))
    (t/is (= #{:geometry-group :content-group :text-content-attribute :text-content-text} (:touched text)))))

(t/deftest keeps-an-intended-fix-override-inside-a-restored-board
  (let [result (-> (issue-file)
                   (collapse :copy-text :width :fix 120 :touched #{:layout-item-h-sizing})
                   (repair))]
    (t/is (repaired? result :copy-board))
    (t/is (not (repaired? result :copy-text)))
    (t/is (= :fix (:layout-item-h-sizing (shape result :copy-text))))))

(t/deftest restores-a-hug-axis-of-a-copy
  (let [result (-> (issue-file)
                   (set-sizing :main-board :layout-item-h-sizing :auto)
                   (set-sizing :copy-board :layout-item-h-sizing :fix)
                   (collapse :copy-board :width :fix 0.01 :touched #{:layout-item-h-sizing})
                   (repair))]
    (t/is (= :auto (:layout-item-h-sizing (shape result :copy-board))))
    (t/is (nil? (:touched (shape result :copy-board))))))

(t/deftest keeps-an-intended-fix-override-of-a-copy
  (let [file   (-> (issue-file)
                   (collapse :copy-board :width :fix 40 :touched #{:geometry-group :layout-item-h-sizing})
                   (collapse :copy-text :width :fix 30 :touched #{:layout-item-h-sizing}))
        result (repair file)]
    (t/is (empty? (:ids result)))
    (t/is (identical? (:objects (thf/current-page file)) (:objects result)))))

(t/deftest does-not-restore-a-copy-under-a-hug-parent
  (let [file   (-> (issue-file)
                   (set-sizing :copy-row :layout-item-h-sizing :auto))
        result (repair file)]
    (t/is (empty? (:ids result)))
    (t/is (= :fix (:layout-item-h-sizing (shape result :copy-board))))
    (t/is (identical? (:objects (thf/current-page file)) (:objects result)))))

(t/deftest does-not-restore-a-copy-whose-fill-siblings-would-collapse-it
  (let [result (-> (thf/sample-file :file1)
                   (column-frame :main-col)
                   (add-text :main-a :main-col :layout-item-h-sizing :fill)
                   (tho/add-rect :main-b :parent-label :main-col :width 40 :height 20
                                 :layout-item-h-sizing :fill)
                   (thc/make-component :component1 :main-col)
                   (thc/instantiate-component :component1 :copy-col :children-labels [:c1 :c2])
                   (copy-of :main-a :copy-a)
                   (set-sizing :copy-col :layout-item-h-sizing :auto)
                   (collapse :copy-a :width :fix 0.01 :touched #{:layout-item-h-sizing})
                   (repair))]
    (t/is (empty? (:ids result)))
    (t/is (= :fix (:layout-item-h-sizing (shape result :copy-a))))))

(t/deftest keeps-a-healthy-frozen-shape-inside-a-restored-board
  (doseq [[label size] [[:copy-rect 40] [:copy-inner 200]]]
    (let [result (-> (issue-file)
                     (collapse label :width :fix size :touched #{:layout-item-h-sizing})
                     (repair))]
      (t/is (repaired? result :copy-board))
      (t/is (not (repaired? result label))))))

(t/deftest keeps-a-frozen-text-with-a-healthy-layout
  (let [data   {:x 0 :y 0 :width 100 :height 16 :fills [] :text "Hello there world"}
        result (-> (issue-file)
                   (collapse :copy-text :width :fix 120 :touched #{:layout-item-h-sizing})
                   (raw-update :copy-text #(assoc % :position-data [data]))
                   (repair))]
    (t/is (not (repaired? result :copy-text)))))

(t/deftest restores-a-thin-rect-copy
  (let [result (-> (issue-file)
                   (set-sizing :main-icon :layout-item-h-sizing :fill)
                   (copy-of :main-icon :copy-icon)
                   (collapse :copy-icon :width :fix 0.01 :touched #{:layout-item-h-sizing})
                   (repair))]
    (t/is (= :fill (:layout-item-h-sizing (shape result :copy-icon))))))

(t/deftest leaves-a-copy-whose-main-is-in-a-missing-library
  (let [library (-> (thf/sample-file :lib :page-label :lib-page)
                    (row-frame :main-row :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8})
                    (column-frame :main-board :parent-label :main-row :layout-item-h-sizing :fill)
                    (thc/make-component :component1 :main-row))
        file    (-> (thf/sample-file :file1)
                    (thc/instantiate-component :component1 :copy-row :library library
                                               :children-labels [:c1])
                    (copy-of :main-board :copy-board)
                    (set-sizing :copy-row :layout-item-h-sizing :fix)
                    (collapse :copy-board :width :fix 0.01 :touched #{:layout-item-h-sizing}))]
    (t/is (empty? (:ids (repair file {}))))
    (t/is (= #{(thi/id :copy-board)} (:ids (repair file {(:id library) library}))))))

;; ---- Nothing else is touched

(t/deftest keeps-a-thin-rect-made-on-purpose
  (let [result (-> (thf/sample-file :file1)
                   (column-frame :col)
                   (tho/add-rect :divider :parent-label :col :width 300 :height 50
                                 :strokes [{:stroke-color "#000000" :stroke-opacity 1 :stroke-width 1
                                            :stroke-style :solid :stroke-alignment :inner}])
                   (collapse :divider :height :fix 0.01)
                   (repair))]
    (t/is (empty? (:ids result)))))

(t/deftest keeps-a-thin-board-without-layout
  (let [result (-> (thf/sample-file :file1)
                   (row-frame :row)
                   (tho/add-frame :spacer :parent-label :row :width 20 :height 20)
                   (collapse :spacer :width :fix 0.01)
                   (repair))]
    (t/is (empty? (:ids result)))))

(t/deftest ignores-shapes-that-are-not-collapsed-layout-children
  (let [result (-> (thf/sample-file :file1)
                   (row-frame :narrow :width 10 :layout-item-h-sizing :fix)
                   (tho/add-rect :fill-child :parent-label :narrow :width 5 :height 5
                                 :layout-item-h-sizing :fill)
                   (tho/add-frame :plain :width 100 :height 100)
                   (tho/add-rect :loose :parent-label :plain :width 0.01 :height 20)
                   (repair))]
    (t/is (empty? (:ids result)))))

(t/deftest ignores-a-healthy-page
  (let [file   (-> (issue-file)
                   (collapse :copy-board :width :fill 31)
                   (collapse :copy-text :width :fill 27))
        result (repair file)]
    (t/is (empty? (:ids result)))
    (t/is (identical? (:objects (thf/current-page file)) (:objects result)))))

;; ---- Non-copy texts and boards

(defn- hug-row-with-fill-board
  [text-params]
  (-> (thf/sample-file :file1)
      (row-frame :row :layout-item-h-sizing :auto)
      (column-frame :inner :parent-label :row :layout-item-h-sizing :fill)
      (add-text :text :inner text-params)
      (collapse :inner :width :fill 0.01)
      (collapse :text :width :fix 0.01)))

(t/deftest fixes-a-text-at-the-width-of-its-stored-layout
  (let [data   {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        result (repair (hug-row-with-fill-board {:position-data [data]}))
        text   (shape result :text)]
    (t/is (= :fix (:layout-item-h-sizing text)))
    (t/is (= 15.0 (double (:width text))))
    (t/is (= :auto (:layout-item-h-sizing (shape result :inner))))
    (t/is (= 1 (count (:position-data text))))))

(t/deftest lays-out-a-collapsed-grid-board-from-stored-text-layout
  (let [data   {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        file   (-> (thf/sample-file :file1)
                   (row-frame :row)
                   (tho/add-frame :grid
                                  :parent-label :row
                                  :width 60 :height 30
                                  :layout :grid
                                  :layout-grid-dir :row
                                  :layout-grid-columns [{:type :flex :value 1}]
                                  :layout-grid-rows [{:type :flex :value 1}]
                                  :layout-grid-cells {}
                                  :layout-padding-type :simple
                                  :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                  :layout-item-h-sizing :fix
                                  :layout-item-v-sizing :fix)
                   (add-text :text :grid :position-data [data])
                   (raw-update :grid
                               (fn [shape]
                                 (let [cell-id (thi/new-id! :grid-cell)]
                                   (assoc shape :layout-grid-cells
                                          {cell-id {:id cell-id
                                                    :row 1 :column 1
                                                    :row-span 1 :column-span 1
                                                    :position :auto
                                                    :align-self :auto :justify-self :auto
                                                    :shapes [(thi/id :text)]}}))))
                   (collapse :grid :width :fix 0.01)
                   (collapse :text :width :fix 0.01))
        result (repair file)
        board  (shape result :grid)
        text   (shape result :text)]
    (t/is (repaired? result :grid))
    (t/is (= [:auto 15.0] [(:layout-item-h-sizing board) (double (:width board))]))
    (t/is (= [:fix 15.0] [(:layout-item-h-sizing text) (double (:width text))]))))

(t/deftest keeps-the-text-and-the-board-when-the-stored-layout-is-collapsed
  (let [chars  (mapv (fn [i] {:x 0 :y (* i 16) :width 8 :height 16 :fills [] :text "I"}) (range 5))
        result (repair (hug-row-with-fill-board {:position-data chars}))]
    (t/is (empty? (:ids result)))))

(t/deftest keeps-the-text-and-the-board-without-a-stored-layout
  (let [result (repair (hug-row-with-fill-board {:position-data nil}))]
    (t/is (empty? (:ids result)))
    (t/is (= :fill (:layout-item-h-sizing (shape result :inner))))))

(t/deftest keeps-a-board-with-another-collapsed-shape-inside
  (let [data   {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        result (-> (hug-row-with-fill-board {:position-data [data]})
                   (tho/add-rect :rect :parent-label :inner :width 20 :height 16
                                 :layout-item-h-sizing :fill)
                   (collapse :rect :width :fill 0.01)
                   (repair))]
    (t/is (repaired? result :text))
    (t/is (not (repaired? result :rect)))
    (t/is (not (repaired? result :inner)))
    (t/is (= :fill (:layout-item-h-sizing (shape result :inner))))))

(t/deftest keeps-a-collapsed-board-when-a-descendant-is-unrepairable
  (let [file   (-> (thf/sample-file :file1)
                   (column-frame :main-board :layout-item-h-sizing :auto)
                   (tho/add-rect :main-rect :parent-label :main-board :width 40 :height 20
                                 :layout-item-h-sizing :fix)
                   (thc/make-component :component1 :main-board)
                   (row-frame :container :width 400)
                   (column-frame :outer :parent-label :container :layout-item-h-sizing :fix)
                   (thc/instantiate-component :component1 :copy-board :parent-label :outer
                                              :children-labels [:copy-rect])
                   (collapse :copy-board :width :fix 0.01 :touched #{:layout-item-h-sizing})
                   (collapse :copy-rect :width :fix 0.01 :touched #{:layout-item-h-sizing})
                   (collapse :outer :width :fix 0.01))
        result (repair file)]
    (t/is (repaired? result :copy-board))
    (t/is (not (repaired? result :copy-rect)))
    (t/is (not (repaired? result :outer)))
    (t/is (= :fix (:layout-item-h-sizing (shape result :outer))))
    (t/is (= :fix (:layout-item-h-sizing (shape result :copy-rect))))))

(t/deftest repairs-twice-nothing
  (let [data    {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        file    (hug-row-with-fill-board {:position-data [data]})
        result  (repair file)
        file'   (update file :data
                        (fn [fdata]
                          (ctpl/update-page fdata (:id (thf/current-page file))
                                            #(assoc % :objects (:objects result)))))
        result' (repair file')]
    (t/is (seq (:ids result)))
    (t/is (empty? (:ids result')))
    (t/is (identical? (:objects result) (:objects result')))))

;; ---- Layout of the repaired boards

(t/deftest lays-out-the-restored-boards-of-the-issue
  (let [result (repair (issue-file))
        board  (shape result :copy-board)
        text   (shape result :copy-text)]
    (t/is (= 31.0 (double (:width board))))
    (t/is (= 27.0 (double (:width text))))
    (t/is (pos? (:a (:transform board))))
    (t/is (pos? (:a (:transform text))))
    (t/is (not (:flip-x board)))
    (t/is (not (:flip-x text)))))

(t/deftest lays-out-a-board-that-starts-to-hug
  (let [data   {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        result (repair (hug-row-with-fill-board {:position-data [data]}))]
    (t/is (= 15.0 (double (:width (shape result :inner)))))
    (t/is (= 15.0 (double (:width (shape result :row)))))))

(t/deftest lays-out-the-hug-boards-above-a-repaired-board
  (let [data   {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        file   (-> (thf/sample-file :file1)
                   (column-frame :outer :layout-item-h-sizing :auto :layout-item-v-sizing :auto)
                   (row-frame :row :parent-label :outer :layout-item-h-sizing :auto)
                   (column-frame :inner :parent-label :row :layout-item-h-sizing :fill)
                   (add-text :text :inner :position-data [data])
                   (collapse :inner :width :fill 0.01)
                   (collapse :text :width :fix 0.01))
        result (repair file)]
    (t/is (= [:auto :fix] [(:layout-item-h-sizing (shape result :inner))
                           (:layout-item-h-sizing (shape result :text))]))
    (t/is (= [15.0 15.0 15.0] (mapv #(double (:width (shape result %))) [:inner :row :outer])))))

(t/deftest lays-out-both-axes-of-a-copy
  (let [result (-> (issue-file)
                   (set-sizing :main-board :layout-item-v-sizing :fill)
                   (set-sizing :copy-board :layout-item-v-sizing :fix)
                   (collapse :copy-board :height :fix 0.01
                             :touched #{:geometry-group :layout-item-h-sizing :layout-item-v-sizing})
                   (repair))
        board  (shape result :copy-board)]
    (t/is (= [:fill :fill] [(:layout-item-h-sizing board) (:layout-item-v-sizing board)]))
    (t/is (= #{:geometry-group} (:touched board)))
    (t/is (= [31.0 34.0] (mapv #(mth/precision % 2) [(:width board) (:height board)])))))

(t/deftest leaves-the-shapes-outside-the-repaired-boards-as-they-are
  (let [file   (-> (issue-file)
                   (tho/add-rect :bystander :width 30 :height 30))
        result (repair file)]
    (t/is (identical? (get (:objects (thf/current-page file)) (thi/id :bystander))
                      (shape result :bystander)))
    (t/is (identical? (get (:objects (thf/current-page file)) (thi/id :container))
                      (shape result :container)))))

(t/deftest moves-the-stored-layout-with-the-text
  (let [data   {:x 0 :y 0 :width 14 :height 16 :fills [] :text "Input"}
        result (repair (hug-row-with-fill-board {:position-data [data]}))
        text   (shape result :text)
        line   (first (:position-data text))]
    (t/is (= 1 (count (:position-data text))))
    (t/is (= 14.0 (double (:width line))))
    (t/is (= (double (:x text)) (double (:x line))))))

(t/deftest keeps-a-valid-size-for-a-restored-copy-in-a-collapsed-board
  (let [result (-> (thf/sample-file :file1)
                   (row-frame :main-row :layout-item-h-sizing :fill :width 60)
                   (thc/make-component :component1 :main-row)
                   (row-frame :box)
                   (thc/instantiate-component :component1 :copy-row :parent-label :box)
                   (set-sizing :copy-row :layout-item-h-sizing :fix)
                   (collapse :copy-row :width :fix 0.01 :touched #{:layout-item-h-sizing})
                   (collapse :box :width :fix 0.01)
                   (repair))
        board  (shape result :copy-row)]
    (t/is (= :fill (:layout-item-h-sizing board)))
    (t/is (>= (:width board) 0))
    (t/is (pos? (:a (:transform board))))))
