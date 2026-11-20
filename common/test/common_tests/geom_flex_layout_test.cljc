;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-flex-layout-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.flex-layout.params :as flparams]
   [app.common.geom.shapes.flex-layout.positions :as flp]
   [app.common.math :as mth]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [clojure.test :as t]))

;; ---- helpers ----

(defn- make-col-frame
  "Minimal col? flex frame with wrap enabled.
  wrap is required for the content-around? predicate to activate."
  [& {:as opts}]
  (cts/setup-shape (merge {:type :frame
                           :layout :flex
                           :layout-flex-dir :column
                           :layout-wrap-type :wrap
                           :x 0 :y 0 :width 200 :height 200}
                          opts)))

(defn- rect->bounds
  "Convert a rect to the 4-point layout-bounds vector expected by gpo/*."
  [rect]
  (grc/rect->points rect))

;; ---- get-base-line (around? branch) ----
;;
;; Bug: in positions.cljc the col? + around? branch had a mis-parenthesised
;; expression `(/ free-width num-lines) 2`, which was parsed as three
;; arguments to `max`:
;;   (max lines-gap-col (/ free-width num-lines) 2)
;; instead of the intended two-argument max with a nested division:
;;   (max lines-gap-col (/ free-width num-lines 2))
;;
;; For a col? layout the cross-axis is horizontal (hv), so the around? offset
;; is applied as hv(delta) — i.e. the delta ends up in (:x base-p).

(t/deftest get-base-line-around-uses-half-per-line-free-width
  (t/testing "col? + content-around? offset is free-width / num-lines / 2"
    ;; Layout: col? wrap, width=200, 3 lines each 20px wide → free-width=140
    ;; lines-gap-col = 0 (no gap defined)
    ;; Expected horizontal offset = max(0, 140/3/2) ≈ 23.33
    ;; Before the bug fix the formula was (max ... (/ 140 3) 2) ≈ 46.67.
    (let [frame  (make-col-frame :layout-align-content :space-around)
          bounds (rect->bounds (grc/make-rect 0 0 200 200))
          ;; 3 lines of 20px each (widths); no row gap
          num-lines    3
          total-width  60
          total-height 0
          base-p (flp/get-base-line frame bounds total-width total-height num-lines)
          free-width (- 200 total-width)
          ;; lines-gap-col = (dec 3) * 0 = 0; max(0, free-width/num-lines/2)
          expected-x (/ free-width num-lines 2)]

      ;; The base point x-coordinate (hv offset) should equal half per-line free space.
      (t/is (mth/close? expected-x (:x base-p) 0.01))))

  (t/testing "col? + content-around? offset respects lines-gap-col minimum"
    ;; When the accumulated column gap exceeds the computed half-per-line value
    ;; max(lines-gap-col, free-width/num-lines/2) returns the gap.
    (let [frame  (make-col-frame :layout-align-content :space-around
                                 :layout-gap {:column-gap 50 :row-gap 0})
          bounds (rect->bounds (grc/make-rect 0 0 200 200))
          ;; 4 lines × 20px = 80px used; free-width=120; half-per-line = 120/4/2 = 15
          ;; lines-gap-col = (dec 4)*50 = 150 → max(150, 15) = 150
          num-lines    4
          total-width  80
          total-height 0
          base-p (flp/get-base-line frame bounds total-width total-height num-lines)
          lines-gap-col (* (dec num-lines) 50)]

      (t/is (mth/close? lines-gap-col (:x base-p) 0.01)))))

;; ---- v-end? guard (drop-line-area) ----
;;
;; Bug: `v-end?` inside `drop-line-area` was guarded by `row?` instead of
;; `col?`, so vertical-end alignment in a column layout was never triggered.
;; We verify the predicate behaviour directly via ctl/v-end?.

(t/deftest v-end-guard-uses-col-not-row
  (t/testing "v-end? is true for col? frame with justify-content :end"
    ;; col? + justify-content=:end → ctl/v-end? must be true
    (let [frame (cts/setup-shape {:type :frame
                                  :layout :flex
                                  :layout-flex-dir :column
                                  :layout-justify-content :end
                                  :x 0 :y 0 :width 100 :height 100})]
      (t/is (true? (ctl/v-end? frame)))))

  (t/testing "v-end? is false for row? frame with only justify-content :end"
    ;; row? + justify-content=:end alone does NOT set v-end?; for row layouts
    ;; v-end? checks align-items, not justify-content.
    (let [frame (cts/setup-shape {:type :frame
                                  :layout :flex
                                  :layout-flex-dir :row
                                  :layout-justify-content :end
                                  :x 0 :y 0 :width 100 :height 100})]
      (t/is (not (ctl/v-end? frame))))))

;; ---- calculate-params paddings ----
;;
;; Adding a flex layout derives the paddings from the distance between the
;; board and its children. A child that overflows the board, or floating
;; point noise after "Resize board to fit content", gave negative paddings
;; that the shape schema rejects, which broke autosave (#12107).

(defn- params-for-child
  [child-attrs]
  (let [parent (cts/setup-shape {:type :frame :x 0 :y 0 :width 100 :height 100})
        child  (cts/setup-shape (merge {:type :rect :parent-id (:id parent)} child-attrs))
        parent (assoc parent :shapes [(:id child)])
        objects {(:id parent) parent (:id child) child}]
    (flparams/calculate-params objects [child] parent)))

(t/deftest calculate-params-keeps-positive-padding
  (t/is (= {:p1 20 :p2 10 :p3 20 :p4 10}
           (:layout-padding (params-for-child {:x 10 :y 20 :width 50 :height 50})))))

(t/deftest calculate-params-clamps-overflowing-child-padding
  (t/is (= {:p1 0 :p2 0 :p3 0 :p4 0}
           (:layout-padding (params-for-child {:x -10 :y -5 :width 150 :height 150})))))

(t/deftest calculate-params-clamps-float-noise-padding
  (let [padding (:layout-padding
                 (params-for-child {:x -1.1728368917829357E-5
                                    :y -2.1457672119140625E-6
                                    :width 100 :height 100}))]
    (t/is (every? #(not (neg? %)) (vals padding)))))

;; ---- calculate-params direction ----
;;
;; Adding a flex layout derives the direction from how the shapes are
;; placed. It used to come from the vector between the centres alone, so
;; a wide box over a narrow one, with the same left edge, read as a row
;; with no gap (#13041).

(defn- stack-params
  "The direction and gaps `calculate-params` picks for rectangles given
  as [x y width height], bottom layer first (the order of the parent)."
  [rects]
  (let [parent  (cts/setup-shape {:type :frame :x 0 :y 0 :width 1000 :height 1000})
        shapes  (mapv (fn [[x y width height]]
                        (cts/setup-shape {:type :rect :parent-id (:id parent) :frame-id (:id parent)
                                          :x x :y y :width width :height height}))
                      rects)
        parent  (assoc parent :shapes (mapv :id shapes))
        objects (into {(:id parent) parent} (map (juxt :id identity)) shapes)
        params  (flparams/calculate-params objects shapes parent)]
    {:direction  (:layout-flex-dir params)
     :row-gap    (get-in params [:layout-gap :row-gap])
     :column-gap (get-in params [:layout-gap :column-gap])}))

(defn- column [direction gap] {:direction direction :row-gap gap :column-gap 0})
(defn- row [direction gap] {:direction direction :row-gap 0 :column-gap gap})

(t/deftest calculate-params-wide-box-over-narrow-box-is-a-column
  ;; The case of the report: 10 px between a 300 and a 50 wide box
  (t/is (= (column :column-reverse 10)
           (stack-params [[0 0 300 40] [0 50 50 40]])))
  (t/is (= (column :column 10)
           (stack-params [[0 50 50 40] [0 0 300 40]]))))

(t/deftest calculate-params-equal-boxes-stacked-keep-their-gap
  (t/is (= (column :column-reverse 20)
           (stack-params [[0 0 100 40] [0 60 100 40]])))
  (t/is (= (column :column 20)
           (stack-params [[0 60 100 40] [0 0 100 40]]))))

(t/deftest calculate-params-direction-does-not-depend-on-the-widest-box-layer
  ;; Three left aligned boxes, 10 px apart, whichever one is created last
  (t/is (= (column :column-reverse 10)
           (stack-params [[0 0 300 20] [0 30 120 20] [0 60 200 20]])))
  (t/is (= (column :column 10)
           (stack-params [[0 60 200 20] [0 30 120 20] [0 0 300 20]]))))

(t/deftest calculate-params-tall-box-beside-short-box-is-a-row
  (t/is (= (row :row-reverse 10)
           (stack-params [[0 0 40 300] [50 0 40 50]])))
  (t/is (= (row :row 10)
           (stack-params [[50 0 40 50] [0 0 40 300]]))))

(t/deftest calculate-params-staggered-chain-is-a-column
  (t/is (= (column :column-reverse 20)
           (stack-params [[0 0 100 40] [30 60 100 40] [60 120 100 40]]))))

(t/deftest calculate-params-touching-boxes-have-no-gap
  (t/is (= (column :column-reverse 0)
           (stack-params [[0 0 100 40] [0 40 100 40]]))))

(t/deftest calculate-params-without-a-clear-axis-follows-the-centres
  ;; A grid is laid out along both axes, a diagonal pair and boxes that
  ;; cover each other along neither: the vector between the centres
  ;; decides, as it did before
  (t/testing "grid"
    (t/is (= {:direction :row-reverse :row-gap 0 :column-gap 0}
             (stack-params [[0 0 100 40] [120 0 100 40] [0 60 100 40] [120 60 100 40]]))))
  (t/testing "diagonal"
    (t/is (= (row :row-reverse 50)
             (stack-params [[0 0 100 40] [150 100 100 40]])))
    (t/is (= (row :row 50)
             (stack-params [[150 100 100 40] [0 0 100 40]]))))
  (t/testing "partial overlap"
    (t/is (= {:direction :column-reverse :row-gap 0 :column-gap 0}
             (stack-params [[0 0 100 100] [50 50 100 100]])))))
