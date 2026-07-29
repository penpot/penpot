;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-flex-layout-test
  (:require
   [app.common.geom.rect :as grc]
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
