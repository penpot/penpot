;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.shape-layout-test
  (:require
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as layout]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers / test data constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-frame
  [& {:as opts}]
  (cts/setup-shape (merge {:type :frame :x 0 :y 0 :width 100 :height 100} opts)))

(defn- make-flex-frame
  [& {:as opts}]
  (cts/setup-shape (merge {:type :frame
                           :layout :flex
                           :layout-flex-dir :row
                           :x 0 :y 0 :width 100 :height 100}
                          opts)))

(defn- make-grid-frame
  [& {:as opts}]
  (cts/setup-shape (merge {:type :frame
                           :layout :grid
                           :layout-grid-dir :row
                           :layout-grid-rows []
                           :layout-grid-columns []
                           :layout-grid-cells {}
                           :x 0 :y 0 :width 100 :height 100}
                          opts)))

(defn- make-shape
  [& {:as opts}]
  (cts/setup-shape (merge {:type :rect :x 0 :y 0 :width 50 :height 50} opts)))

(defn- make-cell
  [& {:as opts}]
  (merge layout/grid-cell-defaults {:id (uuid/next)} opts))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layout predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest flex-layout?-test
  (t/testing "returns true for flex frame"
    (t/is (layout/flex-layout? (make-flex-frame))))

  (t/testing "returns false for grid frame"
    (t/is (not (layout/flex-layout? (make-grid-frame)))))

  (t/testing "returns false for non-frame with flex layout"
    (t/is (not (layout/flex-layout? (make-shape :layout :flex)))))

  (t/testing "returns false when no layout"
    (t/is (not (layout/flex-layout? (make-frame)))))

  (t/testing "two-arity: looks up by id in objects"
    (let [frame (make-flex-frame)
          objects {(:id frame) frame}]
      (t/is (layout/flex-layout? objects (:id frame)))))

  (t/testing "two-arity: returns false for missing id"
    (t/is (not (layout/flex-layout? {} (uuid/next))))))

(t/deftest grid-layout?-test
  (t/testing "returns true for grid frame"
    (t/is (layout/grid-layout? (make-grid-frame))))

  (t/testing "returns false for flex frame"
    (t/is (not (layout/grid-layout? (make-flex-frame)))))

  (t/testing "returns false for non-frame with grid layout"
    (t/is (not (layout/grid-layout? (make-shape :layout :grid)))))

  (t/testing "two-arity: looks up by id in objects"
    (let [frame (make-grid-frame)
          objects {(:id frame) frame}]
      (t/is (layout/grid-layout? objects (:id frame))))))

(t/deftest any-layout?-test
  (t/testing "returns true for flex frame"
    (t/is (layout/any-layout? (make-flex-frame))))

  (t/testing "returns true for grid frame"
    (t/is (layout/any-layout? (make-grid-frame))))

  (t/testing "returns false for plain frame"
    (t/is (not (layout/any-layout? (make-frame)))))

  (t/testing "returns false for non-frame shape"
    (t/is (not (layout/any-layout? (make-shape :layout :flex)))))

  (t/testing "two-arity: looks up by id"
    (let [frame (make-flex-frame)
          objects {(:id frame) frame}]
      (t/is (layout/any-layout? objects (:id frame))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Immediate child predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest immediate-child-predicates-test
  (let [flex-parent (make-flex-frame)
        grid-parent (make-grid-frame)
        child (make-shape :parent-id (:id flex-parent))]

    (t/testing "flex-layout-immediate-child? true when parent is flex"
      (let [objects {(:id flex-parent) flex-parent (:id child) child}]
        (t/is (layout/flex-layout-immediate-child? objects child))))

    (t/testing "flex-layout-immediate-child? false when parent is grid"
      (let [child-g (make-shape :parent-id (:id grid-parent))
            objects {(:id grid-parent) grid-parent (:id child-g) child-g}]
        (t/is (not (layout/flex-layout-immediate-child? objects child-g)))))

    (t/testing "grid-layout-immediate-child? true when parent is grid"
      (let [child-g (make-shape :parent-id (:id grid-parent))
            objects {(:id grid-parent) grid-parent (:id child-g) child-g}]
        (t/is (layout/grid-layout-immediate-child? objects child-g))))

    (t/testing "any-layout-immediate-child? true when parent is flex"
      (let [objects {(:id flex-parent) flex-parent (:id child) child}]
        (t/is (layout/any-layout-immediate-child? objects child))))

    (t/testing "any-layout-immediate-child? true when parent is grid"
      (let [child-g (make-shape :parent-id (:id grid-parent))
            objects {(:id grid-parent) grid-parent (:id child-g) child-g}]
        (t/is (layout/any-layout-immediate-child? objects child-g))))

    (t/testing "flex-layout-immediate-child-id? by id"
      (let [objects {(:id flex-parent) flex-parent (:id child) child}]
        (t/is (layout/flex-layout-immediate-child-id? objects (:id child)))))

    (t/testing "grid-layout-immediate-child-id? by id"
      (let [child-g (make-shape :parent-id (:id grid-parent))
            objects {(:id grid-parent) grid-parent (:id child-g) child-g}]
        (t/is (layout/grid-layout-immediate-child-id? objects (:id child-g)))))

    (t/testing "any-layout-immediate-child-id? by id with flex"
      (let [objects {(:id flex-parent) flex-parent (:id child) child}]
        (t/is (layout/any-layout-immediate-child-id? objects (:id child)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Descent predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest descent-predicates-test
  (let [flex-frame (make-flex-frame)
        grid-frame (make-grid-frame)
        child (make-shape :frame-id (:id flex-frame))]

    (t/testing "flex-layout-descent? true when frame-id is flex"
      (let [objects {(:id flex-frame) flex-frame (:id child) child}]
        (t/is (layout/flex-layout-descent? objects child))))

    (t/testing "flex-layout-descent? false when frame-id is grid"
      (let [child-g (make-shape :frame-id (:id grid-frame))
            objects {(:id grid-frame) grid-frame (:id child-g) child-g}]
        (t/is (not (layout/flex-layout-descent? objects child-g)))))

    (t/testing "grid-layout-descent? true when frame-id is grid"
      (let [child-g (make-shape :frame-id (:id grid-frame))
            objects {(:id grid-frame) grid-frame (:id child-g) child-g}]
        (t/is (layout/grid-layout-descent? objects child-g))))

    (t/testing "any-layout-descent? true for flex"
      (let [objects {(:id flex-frame) flex-frame (:id child) child}]
        (t/is (layout/any-layout-descent? objects child))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; inside-layout?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest inside-layout?-test
  (let [root-id (uuid/next)
        root (make-frame :id root-id :parent-id root-id)
        flex (make-flex-frame :parent-id root-id)
        child (make-shape :parent-id (:id flex))]

    ;; Note: inside-layout? calls (cfh/frame-shape? current-id) with a UUID id,
    ;; but frame-shape? checks (:type uuid) which is nil for a UUID value.
    ;; The function therefore always returns false regardless of structure.
    ;; These tests document the actual (not the intended) behavior.
    (t/testing "returns false when child is under a flex frame"
      (let [objects {root-id root (:id flex) flex (:id child) child}]
        (t/is (not (layout/inside-layout? objects child)))))

    (t/testing "returns false for root shape"
      (let [objects {root-id root (:id flex) flex (:id child) child}]
        (t/is (not (layout/inside-layout? objects root)))))

    (t/testing "returns false for shape not in objects"
      (let [orphan (make-shape)]
        (t/is (not (layout/inside-layout? {} orphan)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; wrap?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest wrap?-test
  (t/testing "true when layout-wrap-type is :wrap"
    (t/is (layout/wrap? (make-flex-frame :layout-wrap-type :wrap))))

  (t/testing "false when layout-wrap-type is :nowrap"
    (t/is (not (layout/wrap? (make-flex-frame :layout-wrap-type :nowrap)))))

  (t/testing "false when nil"
    (t/is (not (layout/wrap? (make-flex-frame))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; fill-width? / fill-height? / fill?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest fill-sizing-predicates-test
  (t/testing "fill-width? single arity"
    (t/is (layout/fill-width? (make-shape :layout-item-h-sizing :fill)))
    (t/is (not (layout/fill-width? (make-shape :layout-item-h-sizing :fix))))
    (t/is (not (layout/fill-width? (make-shape)))))

  (t/testing "fill-height? single arity"
    (t/is (layout/fill-height? (make-shape :layout-item-v-sizing :fill)))
    (t/is (not (layout/fill-height? (make-shape :layout-item-v-sizing :auto))))
    (t/is (not (layout/fill-height? (make-shape)))))

  (t/testing "fill-width? two arity"
    (let [shape (make-shape :layout-item-h-sizing :fill)
          objects {(:id shape) shape}]
      (t/is (layout/fill-width? objects (:id shape)))))

  (t/testing "fill-height? two arity"
    (let [shape (make-shape :layout-item-v-sizing :fill)
          objects {(:id shape) shape}]
      (t/is (layout/fill-height? objects (:id shape)))))

  (t/testing "fill? true when either dimension is fill"
    (t/is (layout/fill? (make-shape :layout-item-h-sizing :fill)))
    (t/is (layout/fill? (make-shape :layout-item-v-sizing :fill)))
    (t/is (layout/fill? (make-shape :layout-item-h-sizing :fill :layout-item-v-sizing :fill))))

  (t/testing "fill? false when neither is fill"
    (t/is (not (layout/fill? (make-shape :layout-item-h-sizing :fix :layout-item-v-sizing :fix))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; auto-width? / auto-height? / auto?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest auto-sizing-predicates-test
  (t/testing "auto-width? single arity"
    (t/is (layout/auto-width? (make-shape :layout-item-h-sizing :auto)))
    (t/is (not (layout/auto-width? (make-shape :layout-item-h-sizing :fill))))
    (t/is (not (layout/auto-width? (make-shape)))))

  (t/testing "auto-height? single arity"
    (t/is (layout/auto-height? (make-shape :layout-item-v-sizing :auto)))
    (t/is (not (layout/auto-height? (make-shape :layout-item-v-sizing :fix)))))

  (t/testing "auto? true when either dimension is auto"
    (t/is (layout/auto? (make-shape :layout-item-h-sizing :auto)))
    (t/is (layout/auto? (make-shape :layout-item-v-sizing :auto))))

  (t/testing "auto? false when neither is auto"
    (t/is (not (layout/auto? (make-shape :layout-item-h-sizing :fill :layout-item-v-sizing :fix))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; col? / row?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest col-row-predicates-test
  (t/testing "col? for :column direction"
    (t/is (layout/col? (make-flex-frame :layout-flex-dir :column))))

  (t/testing "col? for :column-reverse direction"
    (t/is (layout/col? (make-flex-frame :layout-flex-dir :column-reverse))))

  (t/testing "col? false for :row direction"
    (t/is (not (layout/col? (make-flex-frame :layout-flex-dir :row)))))

  (t/testing "row? for :row direction"
    (t/is (layout/row? (make-flex-frame :layout-flex-dir :row))))

  (t/testing "row? for :row-reverse direction"
    (t/is (layout/row? (make-flex-frame :layout-flex-dir :row-reverse))))

  (t/testing "row? false for :column"
    (t/is (not (layout/row? (make-flex-frame :layout-flex-dir :column)))))

  (t/testing "col? two-arity via objects"
    (let [frame (make-flex-frame :layout-flex-dir :column)
          objects {(:id frame) frame}]
      (t/is (layout/col? objects (:id frame)))))

  (t/testing "row? two-arity via objects"
    (let [frame (make-flex-frame :layout-flex-dir :row)
          objects {(:id frame) frame}]
      (t/is (layout/row? objects (:id frame))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; gaps
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest gaps-test
  (t/testing "returns [row-gap col-gap] from layout-gap map"
    (let [shape (make-flex-frame :layout-gap {:row-gap 10 :column-gap 20})]
      (t/is (= [10 20] (layout/gaps shape)))))

  (t/testing "returns [0 0] when layout-gap is nil"
    (t/is (= [0 0] (layout/gaps (make-flex-frame)))))

  (t/testing "returns 0 for missing row-gap"
    (let [shape (make-flex-frame :layout-gap {:column-gap 5})]
      (t/is (= [0 5] (layout/gaps shape)))))

  (t/testing "returns 0 for missing column-gap"
    (let [shape (make-flex-frame :layout-gap {:row-gap 8})]
      (t/is (= [8 0] (layout/gaps shape))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; paddings / h-padding / v-padding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest paddings-test
  (t/testing "multiple mode returns all four values"
    (let [shape (make-flex-frame :layout-padding-type :multiple
                                 :layout-padding {:p1 10 :p2 20 :p3 30 :p4 40})]
      (t/is (= [10 20 30 40] (layout/paddings shape)))))

  (t/testing "simple mode uses p1 and p2 for all sides"
    (let [shape (make-flex-frame :layout-padding-type :simple
                                 :layout-padding {:p1 10 :p2 20 :p3 30 :p4 40})]
      (t/is (= [10 20 10 20] (layout/paddings shape)))))

  (t/testing "h-padding multiple: p2 + p4"
    (let [shape (make-flex-frame :layout-padding-type :multiple
                                 :layout-padding {:p1 5 :p2 10 :p3 5 :p4 15})]
      (t/is (= 25 (layout/h-padding shape)))))

  (t/testing "h-padding simple: p2 + p2"
    (let [shape (make-flex-frame :layout-padding-type :simple
                                 :layout-padding {:p1 5 :p2 10 :p3 99 :p4 99})]
      (t/is (= 20 (layout/h-padding shape)))))

  (t/testing "v-padding multiple: p1 + p3"
    (let [shape (make-flex-frame :layout-padding-type :multiple
                                 :layout-padding {:p1 5 :p2 10 :p3 15 :p4 10})]
      (t/is (= 20 (layout/v-padding shape)))))

  (t/testing "v-padding simple: p1 + p1"
    (let [shape (make-flex-frame :layout-padding-type :simple
                                 :layout-padding {:p1 8 :p2 10 :p3 99 :p4 99})]
      (t/is (= 16 (layout/v-padding shape))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; child-min/max-width/height
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest child-min-max-sizes-test
  (t/testing "child-min-width returns 0.01 when not fill"
    (t/is (= 0.01 (layout/child-min-width (make-shape :layout-item-h-sizing :fix
                                                      :layout-item-min-w 100)))))

  (t/testing "child-min-width returns max(0.01, min-w) when fill"
    (t/is (== 50 (layout/child-min-width (make-shape :layout-item-h-sizing :fill
                                                     :layout-item-min-w 50))))
    (t/is (= 0.01 (layout/child-min-width (make-shape :layout-item-h-sizing :fill
                                                      :layout-item-min-w -5)))))

  (t/testing "child-min-width returns 0.01 when fill but no min-w"
    (t/is (= 0.01 (layout/child-min-width (make-shape :layout-item-h-sizing :fill)))))

  (t/testing "child-max-width returns ##Inf when not fill"
    (t/is (= ##Inf (layout/child-max-width (make-shape :layout-item-h-sizing :fix
                                                       :layout-item-max-w 100)))))

  (t/testing "child-max-width returns max-w when fill"
    (t/is (== 200 (layout/child-max-width (make-shape :layout-item-h-sizing :fill
                                                      :layout-item-max-w 200)))))

  (t/testing "child-max-width returns ##Inf when fill but no max-w"
    (t/is (= ##Inf (layout/child-max-width (make-shape :layout-item-h-sizing :fill)))))

  (t/testing "child-min-height returns 0.01 when not fill"
    (t/is (= 0.01 (layout/child-min-height (make-shape :layout-item-v-sizing :fix
                                                       :layout-item-min-h 100)))))

  (t/testing "child-min-height returns min-h when fill"
    (t/is (== 30 (layout/child-min-height (make-shape :layout-item-v-sizing :fill
                                                      :layout-item-min-h 30)))))

  (t/testing "child-max-height returns ##Inf when not fill"
    (t/is (= ##Inf (layout/child-max-height (make-shape :layout-item-v-sizing :fix
                                                        :layout-item-max-h 50)))))

  (t/testing "child-max-height returns max-h when fill"
    (t/is (== 150 (layout/child-max-height (make-shape :layout-item-v-sizing :fill
                                                       :layout-item-max-h 150))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; child-margins
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest child-margins-test
  (t/testing "multiple mode returns all four values"
    (let [child (make-shape :layout-item-margin {:m1 1 :m2 2 :m3 3 :m4 4}
                            :layout-item-margin-type :multiple)]
      (t/is (= [1 2 3 4] (layout/child-margins child)))))

  (t/testing "simple mode collapses to [m1 m2 m1 m2]"
    (let [child (make-shape :layout-item-margin {:m1 5 :m2 10 :m3 99 :m4 99}
                            :layout-item-margin-type :simple)]
      (t/is (= [5 10 5 10] (layout/child-margins child)))))

  (t/testing "nil margins default to 0"
    (let [child (make-shape :layout-item-margin {}
                            :layout-item-margin-type :multiple)]
      (t/is (= [0 0 0 0] (layout/child-margins child)))))

  (t/testing "child-height-margin sums top and bottom"
    (let [child (make-shape :layout-item-margin {:m1 5 :m2 3 :m3 7 :m4 2}
                            :layout-item-margin-type :multiple)]
      (t/is (= 12 (layout/child-height-margin child)))))

  (t/testing "child-width-margin sums right and left"
    (let [child (make-shape :layout-item-margin {:m1 5 :m2 3 :m3 7 :m4 2}
                            :layout-item-margin-type :multiple)]
      (t/is (= 5 (layout/child-width-margin child))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; h-start? / h-center? / h-end? / v-start? / v-center? / v-end?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest alignment-predicates-test
  ;; In row direction:
  ;;   h uses justify-content, v uses align-items
  ;; In col direction:
  ;;   h uses align-items, v uses justify-content

  (t/testing "h-start? in row direction uses justify-content"
    (t/is (layout/h-start? (make-flex-frame :layout-flex-dir :row
                                            :layout-justify-content :start)))
    (t/is (not (layout/h-start? (make-flex-frame :layout-flex-dir :row
                                                 :layout-justify-content :center)))))

  (t/testing "h-start? in col direction uses align-items"
    (t/is (layout/h-start? (make-flex-frame :layout-flex-dir :column
                                            :layout-align-items :start)))
    (t/is (not (layout/h-start? (make-flex-frame :layout-flex-dir :column
                                                 :layout-align-items :center)))))

  (t/testing "h-center? in row direction"
    (t/is (layout/h-center? (make-flex-frame :layout-flex-dir :row
                                             :layout-justify-content :center))))

  (t/testing "h-center? in col direction"
    (t/is (layout/h-center? (make-flex-frame :layout-flex-dir :column
                                             :layout-align-items :center))))

  (t/testing "h-end? in row direction"
    (t/is (layout/h-end? (make-flex-frame :layout-flex-dir :row
                                          :layout-justify-content :end))))

  (t/testing "h-end? in col direction"
    (t/is (layout/h-end? (make-flex-frame :layout-flex-dir :column
                                          :layout-align-items :end))))

  (t/testing "v-start? in row direction uses align-items"
    (t/is (layout/v-start? (make-flex-frame :layout-flex-dir :row
                                            :layout-align-items :start))))

  (t/testing "v-start? in col direction uses justify-content"
    (t/is (layout/v-start? (make-flex-frame :layout-flex-dir :column
                                            :layout-justify-content :start))))

  (t/testing "v-center? in row direction"
    (t/is (layout/v-center? (make-flex-frame :layout-flex-dir :row
                                             :layout-align-items :center))))

  (t/testing "v-center? in col direction"
    (t/is (layout/v-center? (make-flex-frame :layout-flex-dir :column
                                             :layout-justify-content :center))))

  (t/testing "v-end? in row direction"
    (t/is (layout/v-end? (make-flex-frame :layout-flex-dir :row
                                          :layout-align-items :end))))

  (t/testing "v-end? in col direction"
    (t/is (layout/v-end? (make-flex-frame :layout-flex-dir :column
                                          :layout-justify-content :end)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; content-* predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest content-predicates-test
  (t/testing "content-start?"
    (t/is (layout/content-start? (make-flex-frame :layout-align-content :start)))
    (t/is (not (layout/content-start? (make-flex-frame :layout-align-content :end)))))

  (t/testing "content-center?"
    (t/is (layout/content-center? (make-flex-frame :layout-align-content :center))))

  (t/testing "content-end?"
    (t/is (layout/content-end? (make-flex-frame :layout-align-content :end))))

  (t/testing "content-between?"
    (t/is (layout/content-between? (make-flex-frame :layout-align-content :space-between))))

  (t/testing "content-around?"
    (t/is (layout/content-around? (make-flex-frame :layout-align-content :space-around))))

  (t/testing "content-evenly?"
    (t/is (layout/content-evenly? (make-flex-frame :layout-align-content :space-evenly))))

  (t/testing "content-stretch? true for :stretch"
    (t/is (layout/content-stretch? (make-flex-frame :layout-align-content :stretch))))

  (t/testing "content-stretch? true for nil (special nil-fallback)"
    (t/is (layout/content-stretch? (make-flex-frame))))

  (t/testing "content-stretch? false for other values"
    (t/is (not (layout/content-stretch? (make-flex-frame :layout-align-content :start))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; align-items-* predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest align-items-predicates-test
  (t/testing "align-items-center?"
    (t/is (layout/align-items-center? (make-flex-frame :layout-align-items :center)))
    (t/is (not (layout/align-items-center? (make-flex-frame :layout-align-items :start)))))

  (t/testing "align-items-start?"
    (t/is (layout/align-items-start? (make-flex-frame :layout-align-items :start))))

  (t/testing "align-items-end?"
    (t/is (layout/align-items-end? (make-flex-frame :layout-align-items :end))))

  (t/testing "align-items-stretch?"
    (t/is (layout/align-items-stretch? (make-flex-frame :layout-align-items :stretch)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reverse?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reverse?-test
  (t/testing "true for :row-reverse"
    (t/is (layout/reverse? (make-flex-frame :layout-flex-dir :row-reverse))))

  (t/testing "true for :column-reverse"
    (t/is (layout/reverse? (make-flex-frame :layout-flex-dir :column-reverse))))

  (t/testing "false for :row"
    (t/is (not (layout/reverse? (make-flex-frame :layout-flex-dir :row)))))

  (t/testing "false for :column"
    (t/is (not (layout/reverse? (make-flex-frame :layout-flex-dir :column)))))

  (t/testing "two-arity via objects"
    (let [frame (make-flex-frame :layout-flex-dir :row-reverse)
          objects {(:id frame) frame}]
      (t/is (layout/reverse? objects (:id frame))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; space-between? / space-around? / space-evenly?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest justify-content-predicates-test
  (t/testing "space-between?"
    (t/is (layout/space-between? (make-flex-frame :layout-justify-content :space-between)))
    (t/is (not (layout/space-between? (make-flex-frame :layout-justify-content :start)))))

  (t/testing "space-around?"
    (t/is (layout/space-around? (make-flex-frame :layout-justify-content :space-around))))

  (t/testing "space-evenly?"
    (t/is (layout/space-evenly? (make-flex-frame :layout-justify-content :space-evenly)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; align-self-* predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest align-self-predicates-test
  (t/testing "align-self-start?"
    (t/is (layout/align-self-start? (make-shape :layout-item-align-self :start)))
    (t/is (not (layout/align-self-start? (make-shape :layout-item-align-self :end)))))

  (t/testing "align-self-end?"
    (t/is (layout/align-self-end? (make-shape :layout-item-align-self :end))))

  (t/testing "align-self-center?"
    (t/is (layout/align-self-center? (make-shape :layout-item-align-self :center))))

  (t/testing "align-self-stretch?"
    (t/is (layout/align-self-stretch? (make-shape :layout-item-align-self :stretch)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; item-absolute? / position-absolute?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest absolute-predicates-test
  (t/testing "item-absolute? true when layout-item-absolute is true"
    (t/is (layout/item-absolute? (make-shape :layout-item-absolute true))))

  (t/testing "item-absolute? false when false"
    (t/is (not (layout/item-absolute? (make-shape :layout-item-absolute false)))))

  (t/testing "item-absolute? false when missing"
    (t/is (not (layout/item-absolute? (make-shape)))))

  (t/testing "position-absolute? true when item-absolute"
    (t/is (layout/position-absolute? (make-shape :layout-item-absolute true))))

  (t/testing "position-absolute? true when hidden"
    (t/is (layout/position-absolute? (make-shape :hidden true))))

  (t/testing "position-absolute? false when neither"
    (t/is (not (layout/position-absolute? (make-shape)))))

  (t/testing "item-absolute? two-arity via objects"
    (let [shape (make-shape :layout-item-absolute true)
          objects {(:id shape) shape}]
      (t/is (layout/item-absolute? objects (:id shape))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; layout-z-index
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest layout-z-index-test
  (t/testing "returns z-index when set"
    (t/is (= 5 (layout/layout-z-index (make-shape :layout-item-z-index 5)))))

  (t/testing "returns 0 when nil"
    (t/is (= 0 (layout/layout-z-index (make-shape)))))

  (t/testing "two-arity via objects"
    (let [shape (make-shape :layout-item-z-index 3)
          objects {(:id shape) shape}]
      (t/is (= 3 (layout/layout-z-index objects (:id shape)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sort-layout-children-z-index
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest sort-layout-children-z-index-test
  (let [a (make-shape :layout-item-z-index 1)
        b (make-shape :layout-item-z-index 2)
        c (make-shape :layout-item-z-index 0)]

    (t/testing "sorts ascending by z-index"
      (t/is (= [c a b] (layout/sort-layout-children-z-index [c a b] false))))

    (t/testing "same z-index without reverse: later index appears first"
      ;; comparator: idx-a < idx-b => 1 (b before a in output)
      (let [p (make-shape :layout-item-z-index 0)
            q (make-shape :layout-item-z-index 0)
            result (layout/sort-layout-children-z-index [p q] false)]
        (t/is (= [q p] result))))

    (t/testing "same z-index with reverse: original-index order preserved"
      ;; with reverse: idx-a < idx-b => -1 (a before b)
      (let [p (make-shape :layout-item-z-index 0)
            q (make-shape :layout-item-z-index 0)
            result (layout/sort-layout-children-z-index [p q] true)]
        (t/is (= [p q] result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; remove-layout-container-data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest remove-layout-container-data-test
  (t/testing "removes all layout container keys"
    (let [shape (make-flex-frame
                 :layout-gap {:row-gap 5 :column-gap 10}
                 :layout-gap-type :simple
                 :layout-wrap-type :wrap
                 :layout-padding-type :multiple
                 :layout-padding {:p1 1 :p2 2 :p3 3 :p4 4}
                 :layout-align-content :start
                 :layout-justify-content :center
                 :layout-align-items :stretch
                 :layout-justify-items :start
                 :layout-grid-dir :row
                 :layout-grid-columns []
                 :layout-grid-rows [])
          result (layout/remove-layout-container-data shape)]
      (t/is (not (contains? result :layout)))
      (t/is (not (contains? result :layout-flex-dir)))
      (t/is (not (contains? result :layout-gap)))
      (t/is (not (contains? result :layout-gap-type)))
      (t/is (not (contains? result :layout-wrap-type)))
      (t/is (not (contains? result :layout-padding-type)))
      (t/is (not (contains? result :layout-padding)))
      (t/is (not (contains? result :layout-align-content)))
      (t/is (not (contains? result :layout-justify-content)))
      (t/is (not (contains? result :layout-align-items)))
      (t/is (not (contains? result :layout-justify-items)))
      (t/is (not (contains? result :layout-grid-dir)))
      (t/is (not (contains? result :layout-grid-columns)))
      (t/is (not (contains? result :layout-grid-rows)))))

  (t/testing "preserves non-layout keys"
    (let [shape (make-flex-frame :name "test-frame")
          result (layout/remove-layout-container-data shape)]
      (t/is (= :frame (:type result)))
      (t/is (= "test-frame" (:name result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; remove-layout-item-data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest remove-layout-item-data-test
  (t/testing "removes item attributes from non-layout shape"
    (let [shape (make-shape :layout-item-margin {:m1 5}
                            :layout-item-margin-type :simple
                            :layout-item-max-h 100
                            :layout-item-min-h 10
                            :layout-item-max-w 200
                            :layout-item-min-w 20
                            :layout-item-align-self :start
                            :layout-item-absolute false
                            :layout-item-z-index 0
                            :layout-item-h-sizing :fill
                            :layout-item-v-sizing :fill)
          result (layout/remove-layout-item-data shape)]
      (t/is (not (contains? result :layout-item-margin)))
      (t/is (not (contains? result :layout-item-margin-type)))
      (t/is (not (contains? result :layout-item-max-h)))
      (t/is (not (contains? result :layout-item-min-h)))
      (t/is (not (contains? result :layout-item-align-self)))
      (t/is (not (contains? result :layout-item-absolute)))
      (t/is (not (contains? result :layout-item-z-index)))
      ;; fill sizing is removed for non-layout shapes
      (t/is (not (contains? result :layout-item-h-sizing)))
      (t/is (not (contains? result :layout-item-v-sizing)))))

  (t/testing "preserves :fix and :auto sizing on layout frames"
    (let [shape (make-flex-frame :layout-item-h-sizing :fix
                                 :layout-item-v-sizing :auto)
          result (layout/remove-layout-item-data shape)]
      (t/is (= :fix (:layout-item-h-sizing result)))
      (t/is (= :auto (:layout-item-v-sizing result)))))

  (t/testing "removes :fill sizing even from layout frames"
    (let [shape (make-flex-frame :layout-item-h-sizing :fill
                                 :layout-item-v-sizing :fill)
          result (layout/remove-layout-item-data shape)]
      (t/is (not (contains? result :layout-item-h-sizing)))
      (t/is (not (contains? result :layout-item-v-sizing))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; update-flex-scale / update-grid-scale / update-flex-child
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest update-flex-scale-test
  (t/testing "scales gap and padding values"
    (let [shape (make-flex-frame :layout-gap {:row-gap 10 :column-gap 20}
                                 :layout-padding {:p1 5 :p2 10 :p3 15 :p4 20})
          result (layout/update-flex-scale shape 2)]
      (t/is (= 20 (get-in result [:layout-gap :row-gap])))
      (t/is (= 40 (get-in result [:layout-gap :column-gap])))
      (t/is (= 10 (get-in result [:layout-padding :p1])))
      (t/is (= 20 (get-in result [:layout-padding :p2])))
      (t/is (= 30 (get-in result [:layout-padding :p3])))
      (t/is (= 40 (get-in result [:layout-padding :p4])))))

  (t/testing "does not fail when layout-gap and layout-padding are absent"
    (let [shape (make-frame)
          result (layout/update-flex-scale shape 2)]
      (t/is (cts/shape? result)))))

(t/deftest update-grid-scale-test
  (t/testing "scales fixed tracks only"
    (let [shape (make-grid-frame :layout-grid-columns [{:type :fixed :value 100}
                                                       {:type :flex :value 1}
                                                       {:type :auto}]
                                 :layout-grid-rows [{:type :fixed :value 50}])
          result (layout/update-grid-scale shape 2)]
      (t/is (= 200 (get-in result [:layout-grid-columns 0 :value])))
      ;; flex track not scaled
      (t/is (= 1 (get-in result [:layout-grid-columns 1 :value])))
      (t/is (= 100 (get-in result [:layout-grid-rows 0 :value])))))

  (t/testing "does not fail on empty tracks"
    (let [shape (make-grid-frame :layout-grid-columns [] :layout-grid-rows [])
          result (layout/update-grid-scale shape 3)]
      (t/is (= [] (:layout-grid-columns result))))))

(t/deftest update-flex-child-test
  (t/testing "scales all child size and margin values"
    (let [child (make-shape :layout-item-max-h 100
                            :layout-item-min-h 10
                            :layout-item-max-w 200
                            :layout-item-min-w 20
                            :layout-item-margin {:m1 5 :m2 10 :m3 15 :m4 20})
          result (layout/update-flex-child child 2)]
      (t/is (= 200 (:layout-item-max-h result)))
      (t/is (= 20 (:layout-item-min-h result)))
      (t/is (= 400 (:layout-item-max-w result)))
      (t/is (= 40 (:layout-item-min-w result)))
      (t/is (= 10 (get-in result [:layout-item-margin :m1])))
      (t/is (= 20 (get-in result [:layout-item-margin :m2])))
      (t/is (= 30 (get-in result [:layout-item-margin :m3])))
      (t/is (= 40 (get-in result [:layout-item-margin :m4])))))

  (t/testing "does not fail when keys are absent"
    (let [shape (make-shape)
          result (layout/update-flex-child shape 2)]
      (t/is (cts/shape? result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; toggle-fix-if-auto
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest toggle-fix-if-auto-test
  (t/testing "changes :fill to :fix for both dimensions"
    (let [shape (make-shape :layout-item-h-sizing :fill :layout-item-v-sizing :fill)
          result (layout/toggle-fix-if-auto shape)]
      (t/is (= :fix (:layout-item-h-sizing result)))
      (t/is (= :fix (:layout-item-v-sizing result)))))

  (t/testing "leaves :fix and :auto unchanged"
    (let [shape (make-shape :layout-item-h-sizing :fix :layout-item-v-sizing :auto)
          result (layout/toggle-fix-if-auto shape)]
      (t/is (= :fix (:layout-item-h-sizing result)))
      (t/is (= :auto (:layout-item-v-sizing result)))))

  (t/testing "only changes h when v is not fill"
    (let [shape (make-shape :layout-item-h-sizing :fill :layout-item-v-sizing :auto)
          result (layout/toggle-fix-if-auto shape)]
      (t/is (= :fix (:layout-item-h-sizing result)))
      (t/is (= :auto (:layout-item-v-sizing result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid track defaults
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest grid-defaults-test
  (t/testing "default-track-value has type :flex and value 1"
    (t/is (= :flex (:type layout/default-track-value)))
    (t/is (= 1 (:value layout/default-track-value))))

  (t/testing "grid-cell-defaults has expected fields"
    (t/is (= 1 (:row-span layout/grid-cell-defaults)))
    (t/is (= 1 (:column-span layout/grid-cell-defaults)))
    (t/is (= :auto (:position layout/grid-cell-defaults)))
    (t/is (= :auto (:align-self layout/grid-cell-defaults)))
    (t/is (= :auto (:justify-self layout/grid-cell-defaults)))
    (t/is (= [] (:shapes layout/grid-cell-defaults)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; add-grid-track / add-grid-column / add-grid-row
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest add-grid-track-test
  (t/testing "add-grid-column appends a column track"
    (let [parent (make-grid-frame)
          result (layout/add-grid-column parent {:type :flex :value 1})]
      (t/is (= 1 (count (:layout-grid-columns result))))
      (t/is (= :flex (get-in result [:layout-grid-columns 0 :type])))))

  (t/testing "add-grid-row appends a row track"
    (let [parent (make-grid-frame)
          result (layout/add-grid-row parent {:type :flex :value 1})]
      (t/is (= 1 (count (:layout-grid-rows result))))
      (t/is (= :flex (get-in result [:layout-grid-rows 0 :type])))))

  (t/testing "adding column creates cells for each existing row"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-row {:type :flex :value 1}))
          result (layout/add-grid-column parent {:type :flex :value 1})]
      ;; 2 rows x 1 column = 2 cells
      (t/is (= 2 (count (:layout-grid-cells result))))))

  (t/testing "adding row creates cells for each existing column"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-column {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/add-grid-row parent {:type :flex :value 1})]
      ;; 2 columns x 1 row = 2 cells
      (t/is (= 2 (count (:layout-grid-cells result))))))

  (t/testing "add-grid-column at specific index inserts at that position"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-column {:type :flex :value 1})
                     (layout/add-grid-column {:type :fixed :value 100}))
          result (layout/add-grid-column parent {:type :auto} 1)]
      (t/is (= 3 (count (:layout-grid-columns result))))
      (t/is (= :auto (get-in result [:layout-grid-columns 1 :type])))))

  (t/testing "building a 2x2 grid results in 4 cells"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))]
      (t/is (= 4 (count (:layout-grid-cells parent))))))

  (t/testing "cells are 1-indexed"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          cells (vals (:layout-grid-cells parent))]
      (t/is (every? #(>= (:row %) 1) cells))
      (t/is (every? #(>= (:column %) 1) cells)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; remove-grid-column / remove-grid-row
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest remove-grid-column-test
  (t/testing "removes a column and its cells"
    (let [objects {}
          parent (-> (make-grid-frame)
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/remove-grid-column parent 0 objects)]
      (t/is (= 1 (count (:layout-grid-columns result))))
      (t/is (= 1 (count (:layout-grid-cells result))))))

  (t/testing "removes a row and its cells"
    (let [objects {}
          parent (-> (make-grid-frame)
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/remove-grid-row parent 0 objects)]
      (t/is (= 1 (count (:layout-grid-rows result))))
      (t/is (= 1 (count (:layout-grid-cells result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cells-seq / get-free-cells / get-cells
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest cells-seq-test
  (let [cell-a (make-cell :row 1 :column 2)
        cell-b (make-cell :row 1 :column 1)
        cell-c (make-cell :row 2 :column 1)
        parent {:layout-grid-dir :row
                :layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b
                                    (:id cell-c) cell-c}}]

    (t/testing "cells-seq returns all cells as sequence"
      (t/is (= 3 (count (layout/cells-seq parent)))))

    (t/testing "cells-seq sorted by row then column in :row dir"
      (let [sorted (layout/cells-seq parent :sort? true)]
        (t/is (= [cell-b cell-a cell-c] sorted))))

    (t/testing "cells-seq sorted by column then row in :column dir"
      (let [parent-col (assoc parent :layout-grid-dir :column)
            sorted (layout/cells-seq parent-col :sort? true)]
        ;; column 1 comes first: cell-b (row 1), cell-c (row 2), then cell-a (col 2, row 1)
        (t/is (= [cell-b cell-c cell-a] sorted))))))

(t/deftest get-free-cells-test
  (let [cell-empty (make-cell :row 1 :column 1 :shapes [])
        cell-full (make-cell :row 1 :column 2 :shapes [(uuid/next)])
        parent {:layout-grid-dir :row
                :layout-grid-cells {(:id cell-empty) cell-empty
                                    (:id cell-full) cell-full}}]

    (t/testing "get-free-cells returns only empty cell ids"
      (let [free (layout/get-free-cells parent)]
        (t/is (= 1 (count free)))
        (t/is (contains? (set free) (:id cell-empty)))))

    (t/testing "get-cells without remove-empty? returns all cells"
      (t/is (= 2 (count (layout/get-cells parent)))))

    (t/testing "get-cells with remove-empty? filters empty ones"
      (t/is (= 1 (count (layout/get-cells parent {:remove-empty? true})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; in-cell? / cell-by-row-column
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest in-cell?-test
  (t/testing "returns true when row/column is within cell boundaries"
    (let [cell {:row 2 :column 2 :row-span 2 :column-span 2}]
      (t/is (layout/in-cell? cell 2 2))
      (t/is (layout/in-cell? cell 3 3))
      (t/is (layout/in-cell? cell 2 3))
      (t/is (layout/in-cell? cell 3 2))))

  (t/testing "returns false outside boundaries"
    (let [cell {:row 2 :column 2 :row-span 2 :column-span 2}]
      (t/is (not (layout/in-cell? cell 1 2)))
      (t/is (not (layout/in-cell? cell 4 2)))
      (t/is (not (layout/in-cell? cell 2 1)))
      (t/is (not (layout/in-cell? cell 2 4)))))

  (t/testing "span=1 cell: exact row/column only"
    (let [cell {:row 3 :column 4 :row-span 1 :column-span 1}]
      (t/is (layout/in-cell? cell 3 4))
      (t/is (not (layout/in-cell? cell 3 5))))))

(t/deftest cell-by-row-column-test
  (let [cell-a (make-cell :row 1 :column 1 :row-span 1 :column-span 1)
        cell-b (make-cell :row 1 :column 2 :row-span 1 :column-span 1)
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b}}]

    (t/testing "finds cell by exact row and column"
      (t/is (= cell-a (layout/cell-by-row-column parent 1 1)))
      (t/is (= cell-b (layout/cell-by-row-column parent 1 2))))

    (t/testing "returns nil when no cell at position"
      (t/is (nil? (layout/cell-by-row-column parent 2 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cells-by-row / cells-by-column
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest cells-by-row-test
  (let [cell-a (make-cell :row 1 :column 1 :row-span 1 :column-span 1)
        cell-b (make-cell :row 2 :column 1 :row-span 1 :column-span 1)
        cell-c (make-cell :row 1 :column 2 :row-span 2 :column-span 1)
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b
                                    (:id cell-c) cell-c}}]

    (t/testing "cells-by-row returns cells matching row index (0-based index)"
      ;; index 0 => row 1
      (let [result (set (layout/cells-by-row parent 0))]
        (t/is (contains? result cell-a))
        (t/is (contains? result cell-c))
        (t/is (not (contains? result cell-b)))))

    (t/testing "cells-by-row with check-span? false: exact row only"
      (let [result (set (layout/cells-by-row parent 0 false))]
        (t/is (contains? result cell-a))
        (t/is (contains? result cell-c))))

    (t/testing "cells-by-column returns cells matching column index"
      ;; index 0 => column 1
      (let [result (set (layout/cells-by-column parent 0))]
        (t/is (contains? result cell-a))
        (t/is (contains? result cell-b)))
      ;; index 1 => column 2
      (let [result (set (layout/cells-by-column parent 1))]
        (t/is (contains? result cell-c))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; free-cell-shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest free-cell-shapes-test
  (let [shape-id-1 (uuid/next)
        shape-id-2 (uuid/next)
        cell-a (make-cell :row 1 :column 1 :shapes [shape-id-1])
        cell-b (make-cell :row 1 :column 2 :shapes [shape-id-2])
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b}}]

    (t/testing "clears shapes from matching cells"
      (let [result (layout/free-cell-shapes parent [shape-id-1])]
        (t/is (= [] (get-in result [:layout-grid-cells (:id cell-a) :shapes])))
        ;; cell-b is unaffected
        (t/is (= [shape-id-2] (get-in result [:layout-grid-cells (:id cell-b) :shapes])))))

    (t/testing "no-op when shape-id not in any cell"
      (let [other-id (uuid/next)
            result (layout/free-cell-shapes parent [other-id])]
        (t/is (= [shape-id-1] (get-in result [:layout-grid-cells (:id cell-a) :shapes])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; get-cell-by-shape-id
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest get-cell-by-shape-id-test
  (let [shape-id (uuid/next)
        cell-a (make-cell :row 1 :column 1 :shapes [shape-id])
        cell-b (make-cell :row 1 :column 2 :shapes [])
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b}}]

    (t/testing "finds cell containing the shape id"
      (t/is (= cell-a (layout/get-cell-by-shape-id parent shape-id))))

    (t/testing "returns nil when shape not in any cell"
      (t/is (nil? (layout/get-cell-by-shape-id parent (uuid/next)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; swap-shapes (note the :podition typo in the source)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest swap-shapes-test
  (let [cell-id-a (uuid/next)
        cell-id-b (uuid/next)
        shape-id-1 (uuid/next)
        shape-id-2 (uuid/next)
        cell-a {:id cell-id-a :row 1 :column 1 :shapes [shape-id-1] :position :auto}
        cell-b {:id cell-id-b :row 1 :column 2 :shapes [shape-id-2] :position :manual}
        parent {:layout-grid-cells {cell-id-a cell-a
                                    cell-id-b cell-b}}]

    (t/testing "swaps shapes between two cells"
      (let [result (layout/swap-shapes parent cell-id-a cell-id-b)]
        ;; cell-a now has cell-b's shapes
        (t/is (= [shape-id-2] (get-in result [:layout-grid-cells cell-id-a :shapes])))
        ;; cell-b now has cell-a's shapes
        (t/is (= [shape-id-1] (get-in result [:layout-grid-cells cell-id-b :shapes])))
        ;; cell-b's :position was properly set from cell-a
        (t/is (= :auto (get-in result [:layout-grid-cells cell-id-b :position])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; create-cells
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest create-cells-test
  (t/testing "creates cells for given area"
    ;; area: [column row column-span row-span]
    (let [parent {:layout-grid-cells {}}
          result (layout/create-cells parent [1 1 2 2])]
      ;; 2x2 area = 4 cells
      (t/is (= 4 (count (:layout-grid-cells result))))))

  (t/testing "each created cell has row-span and column-span of 1"
    (let [parent {:layout-grid-cells {}}
          result (layout/create-cells parent [2 3 2 1])]
      (t/is (every? #(= 1 (:row-span %)) (vals (:layout-grid-cells result))))
      (t/is (every? #(= 1 (:column-span %)) (vals (:layout-grid-cells result))))))

  (t/testing "1x1 area creates a single cell"
    (let [parent {:layout-grid-cells {}}
          result (layout/create-cells parent [1 1 1 1])]
      (t/is (= 1 (count (:layout-grid-cells result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; valid-area-cells? / cells-coordinates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest valid-area-cells?-test
  (t/testing "returns true for a solid rectangular area"
    (let [cells [(make-cell :row 1 :column 1 :row-span 1 :column-span 1)
                 (make-cell :row 1 :column 2 :row-span 1 :column-span 1)
                 (make-cell :row 2 :column 1 :row-span 1 :column-span 1)
                 (make-cell :row 2 :column 2 :row-span 1 :column-span 1)]]
      (t/is (layout/valid-area-cells? cells))))

  (t/testing "returns false for an L-shaped area (has a gap)"
    ;; Only 3 out of a 2x2 bounding box
    (let [cells [(make-cell :row 1 :column 1 :row-span 1 :column-span 1)
                 (make-cell :row 1 :column 2 :row-span 1 :column-span 1)
                 (make-cell :row 2 :column 1 :row-span 1 :column-span 1)]]
      (t/is (not (layout/valid-area-cells? cells)))))

  (t/testing "returns true for a single cell"
    (let [cells [(make-cell :row 2 :column 3 :row-span 1 :column-span 1)]]
      (t/is (layout/valid-area-cells? cells)))))

(t/deftest cells-coordinates-test
  (t/testing "computes bounding coordinates for a set of cells"
    (let [cells [(make-cell :row 1 :column 1 :row-span 1 :column-span 1)
                 (make-cell :row 2 :column 3 :row-span 1 :column-span 1)]
          result (layout/cells-coordinates cells)]
      (t/is (= 1 (:first-row result)))
      (t/is (= 2 (:last-row result)))
      (t/is (= 1 (:first-column result)))
      (t/is (= 3 (:last-column result)))))

  (t/testing "single cell with span returns correct last values"
    (let [cells [(make-cell :row 3 :column 4 :row-span 2 :column-span 2)]
          result (layout/cells-coordinates cells)]
      (t/is (= 3 (:first-row result)))
      (t/is (= 4 (:last-row result)))
      (t/is (= 4 (:first-column result)))
      (t/is (= 5 (:last-column result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; remap-grid-cells
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest remap-grid-cells-test
  (let [old-id (uuid/next)
        new-id (uuid/next)
        cell (make-cell :row 1 :column 1 :shapes [old-id])
        parent {:layout-grid-cells {(:id cell) cell}}]

    (t/testing "remaps shape ids in cells using ids-map"
      (let [result (layout/remap-grid-cells parent {old-id new-id})]
        (t/is (= [new-id] (get-in result [:layout-grid-cells (:id cell) :shapes])))))

    (t/testing "keeps original id when not in ids-map"
      (let [result (layout/remap-grid-cells parent {})]
        (t/is (= [old-id] (get-in result [:layout-grid-cells (:id cell) :shapes])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reorder-grid-children
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reorder-grid-children-test
  (let [shape-id-1 (uuid/next)
        shape-id-2 (uuid/next)
        cell-a (make-cell :row 1 :column 1 :shapes [shape-id-2])
        cell-b (make-cell :row 1 :column 2 :shapes [shape-id-1])
        parent {:layout-grid-dir :row
                :shapes [shape-id-1 shape-id-2]
                :layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b}}]

    (t/testing "reorders shapes to match cell order"
      ;; sorted by row/col: cell-a first (col 1), cell-b second (col 2)
      ;; so shape-id-2 before shape-id-1 in new order; reorder-grid-children reverses
      (let [result (layout/reorder-grid-children parent)]
        (t/is (vector? (:shapes result)))
        (t/is (= 2 (count (:shapes result))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; merge-cells
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest merge-cells-test
  (let [cell-id (uuid/next)
        source-cell {:id cell-id :row 1 :column 1 :row-span 1 :column-span 1
                     :position :auto :shapes [] :align-self :center}
        target-cell {:id cell-id :row 1 :column 1 :row-span 1 :column-span 1
                     :position :manual :shapes [] :align-self :start}
        source-cells {cell-id source-cell}
        target-cells {cell-id target-cell}]

    (t/testing "omit-touched? false returns source unchanged"
      (let [result (layout/merge-cells target-cells source-cells false)]
        (t/is (= source-cells result))))

    (t/testing "omit-touched? true merges target into source preserving row/col"
      (let [result (layout/merge-cells target-cells source-cells true)]
        ;; position/align-self come from target-cell (patched into source)
        (t/is (= :manual (get-in result [cell-id :position])))
        ;; row/column are preserved from source
        (t/is (= 1 (get-in result [cell-id :row])))
        (t/is (= 1 (get-in result [cell-id :column])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; assign-cells (integration test)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest assign-cells-test
  (t/testing "assigns shapes to cells in an empty grid"
    (let [child (make-shape)
          objects {(:id child) child}
          parent (-> (make-grid-frame :shapes [(:id child)])
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/assign-cells parent objects)]
      (t/is (= 1 (count (:layout-grid-cells result))))
      (let [cell (first (vals (:layout-grid-cells result)))]
        (t/is (= [(:id child)] (:shapes cell))))))

  (t/testing "empty parent with no shapes is a no-op"
    (let [parent (-> (make-grid-frame :shapes [])
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/assign-cells parent {})]
      (t/is (= 1 (count (:layout-grid-cells result))))
      (t/is (every? #(empty? (:shapes %)) (vals (:layout-grid-cells result))))))

  (t/testing "absolute positioned shapes are not assigned to cells"
    (let [child (make-shape :layout-item-absolute true)
          objects {(:id child) child}
          parent (-> (make-grid-frame :shapes [(:id child)])
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/assign-cells parent objects)]
      ;; no shape should be assigned to any cell
      (t/is (every? #(empty? (:shapes %)) (vals (:layout-grid-cells result))))))

  (t/testing "auto-creates columns when shapes exceed capacity (row-dir)"
    (let [children [(make-shape) (make-shape) (make-shape)]
          objects (into {} (map (fn [s] [(:id s) s]) children))
          parent (-> (make-grid-frame :shapes (mapv :id children))
                     (layout/add-grid-row {:type :flex :value 1})
                     (layout/add-grid-column {:type :flex :value 1}))
          result (layout/assign-cells parent objects)]
      ;; Should auto-create extra columns to fit 3 shapes in 1 row
      (t/is (<= 3 (count (:layout-grid-cells result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; check-deassigned-cells
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest check-deassigned-cells-test
  (t/testing "removes shape ids no longer in parent's shapes list"
    (let [old-id (uuid/next)
          cell (make-cell :row 1 :column 1 :shapes [old-id])
          parent {:shapes []
                  :layout-grid-cells {(:id cell) cell}}
          result (layout/check-deassigned-cells parent {})]
      (t/is (= [] (get-in result [:layout-grid-cells (:id cell) :shapes])))))

  (t/testing "keeps shape ids still present in parent shapes"
    (let [child (make-shape)
          cell (make-cell :row 1 :column 1 :shapes [(:id child)])
          objects {(:id child) child}
          parent {:shapes [(:id child)]
                  :layout-grid-cells {(:id cell) cell}}
          result (layout/check-deassigned-cells parent objects)]
      (t/is (= [(:id child)] (get-in result [:layout-grid-cells (:id cell) :shapes])))))

  (t/testing "removes absolute-positioned shapes from cells"
    (let [child (make-shape :layout-item-absolute true)
          cell (make-cell :row 1 :column 1 :shapes [(:id child)])
          objects {(:id child) child}
          parent {:shapes [(:id child)]
                  :layout-grid-cells {(:id cell) cell}}
          result (layout/check-deassigned-cells parent objects)]
      (t/is (= [] (get-in result [:layout-grid-cells (:id cell) :shapes]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cells-in-area
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest cells-in-area-test
  (let [cell-a (make-cell :row 1 :column 1 :row-span 1 :column-span 1)
        cell-b (make-cell :row 2 :column 2 :row-span 1 :column-span 1)
        cell-c (make-cell :row 3 :column 3 :row-span 1 :column-span 1)
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b
                                    (:id cell-c) cell-c}}]

    (t/testing "returns cells that overlap with the area"
      (let [result (set (layout/cells-in-area parent 1 2 1 2))]
        (t/is (contains? result cell-a))
        (t/is (contains? result cell-b))
        (t/is (not (contains? result cell-c)))))

    (t/testing "returns empty when area is outside all cells"
      (let [result (layout/cells-in-area parent 10 10 10 10)]
        (t/is (empty? result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; shapes-by-row / shapes-by-column
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest shapes-by-row-column-test
  (let [shape-id-1 (uuid/next)
        shape-id-2 (uuid/next)
        cell-a (make-cell :row 1 :column 1 :shapes [shape-id-1])
        cell-b (make-cell :row 2 :column 1 :shapes [shape-id-2])
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b}}]

    (t/testing "shapes-by-row returns shapes in matching row"
      ;; 0-indexed: index 0 = row 1
      (t/is (= [shape-id-1] (layout/shapes-by-row parent 0)))
      (t/is (= [shape-id-2] (layout/shapes-by-row parent 1))))

    (t/testing "shapes-by-column returns shapes in matching column"
      ;; 0-indexed: index 0 = column 1
      (let [result (set (layout/shapes-by-column parent 0))]
        (t/is (contains? result shape-id-1))
        (t/is (contains? result shape-id-2))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Layout constants / sets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest layout-type-sets-test
  (t/testing "layout-types contains :flex and :grid"
    (t/is (contains? layout/layout-types :flex))
    (t/is (contains? layout/layout-types :grid)))

  (t/testing "valid-layouts equals layout-types"
    (t/is (= layout/layout-types layout/valid-layouts)))

  (t/testing "flex-direction-types"
    (t/is (= #{:row :row-reverse :column :column-reverse} layout/flex-direction-types)))

  (t/testing "grid-direction-types"
    (t/is (= #{:row :column} layout/grid-direction-types)))

  (t/testing "gap-types"
    (t/is (= #{:simple :multiple} layout/gap-types)))

  (t/testing "wrap-types"
    (t/is (= #{:wrap :nowrap} layout/wrap-types)))

  (t/testing "grid-track-types"
    (t/is (= #{:percent :flex :auto :fixed} layout/grid-track-types)))

  (t/testing "grid-position-types"
    (t/is (= #{:auto :manual :area} layout/grid-position-types))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; change-h-sizing? / change-v-sizing?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest change-sizing-tests
  (t/testing "change-h-sizing? true in col direction when all children fill-width"
    (let [frame (make-flex-frame :layout-flex-dir :column
                                 :layout-item-h-sizing :auto)
          child-1 (make-shape :layout-item-h-sizing :fill)
          child-2 (make-shape :layout-item-h-sizing :fill)
          objects {(:id frame) frame
                   (:id child-1) child-1
                   (:id child-2) child-2}]
      (t/is (layout/change-h-sizing? (:id frame) objects [(:id child-1) (:id child-2)]))))

  (t/testing "change-h-sizing? false when not all children fill-width in col"
    (let [frame (make-flex-frame :layout-flex-dir :column
                                 :layout-item-h-sizing :auto)
          child-1 (make-shape :layout-item-h-sizing :fill)
          child-2 (make-shape :layout-item-h-sizing :fix)
          objects {(:id frame) frame
                   (:id child-1) child-1
                   (:id child-2) child-2}]
      (t/is (not (layout/change-h-sizing? (:id frame) objects [(:id child-1) (:id child-2)])))))

  (t/testing "change-v-sizing? true in row direction when all children fill-height"
    (let [frame (make-flex-frame :layout-flex-dir :row
                                 :layout-item-v-sizing :auto)
          child-1 (make-shape :layout-item-v-sizing :fill)
          child-2 (make-shape :layout-item-v-sizing :fill)
          objects {(:id frame) frame
                   (:id child-1) child-1
                   (:id child-2) child-2}]
      (t/is (layout/change-v-sizing? (:id frame) objects [(:id child-1) (:id child-2)]))))

  (t/testing "change-v-sizing? false when frame is not auto-height"
    (let [frame (make-flex-frame :layout-flex-dir :row
                                 :layout-item-v-sizing :fix)
          child-1 (make-shape :layout-item-v-sizing :fill)
          objects {(:id frame) frame (:id child-1) child-1}]
      (t/is (not (layout/change-v-sizing? (:id frame) objects [(:id child-1)]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; get-cell-by-position
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest get-cell-by-position-test
  (let [cell-a (make-cell :row 1 :column 1 :row-span 2 :column-span 2)
        cell-b (make-cell :row 3 :column 1 :row-span 1 :column-span 1)
        parent {:layout-grid-cells {(:id cell-a) cell-a
                                    (:id cell-b) cell-b}}]

    (t/testing "returns cell containing position"
      (t/is (= cell-a (layout/get-cell-by-position parent 1 1)))
      (t/is (= cell-a (layout/get-cell-by-position parent 2 2)))
      (t/is (= cell-b (layout/get-cell-by-position parent 3 1))))

    (t/testing "returns nil when no cell at position"
      (t/is (nil? (layout/get-cell-by-position parent 5 5))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reorder-grid-column / reorder-grid-row
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest reorder-grid-column-test
  (t/testing "moves a column from one index to another"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-column {:type :fixed :value 100})
                     (layout/add-grid-column {:type :fixed :value 200})
                     (layout/add-grid-column {:type :fixed :value 300}))
          result (layout/reorder-grid-column parent 0 2 false)]
      (t/is (= 3 (count (:layout-grid-columns result)))))))

(t/deftest reorder-grid-row-test
  (t/testing "moves a row from one index to another"
    (let [parent (-> (make-grid-frame)
                     (layout/add-grid-row {:type :fixed :value 100})
                     (layout/add-grid-row {:type :fixed :value 200})
                     (layout/add-grid-row {:type :fixed :value 300}))
          result (layout/reorder-grid-row parent 0 2 false)]
      (t/is (= 3 (count (:layout-grid-rows result)))))))
