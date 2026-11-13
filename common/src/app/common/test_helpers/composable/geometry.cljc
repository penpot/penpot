;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.geometry
  "Expected geometry from authored inputs and parent frames. Only primitive
   constraint and transform math is shared with production."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]))

(defn- orientation-matrix
  "Resize uses constraints and dimensions. Parent placement supplies axis
   directions, without applying residual minimum-size scale a second time."
  [shape]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        horizontal (gpt/unit (gpt/point a b))
        vertical (gpt/unit (gpt/point c d))]
    (gmt/matrix (:x horizontal) (:y horizontal) (:x vertical) (:y vertical) 0 0)))

(defn same-placed-axes?
  "Whether `a`, moved from its parent `a-parent` into `a-target`, points its
   axes as `b` does moved from `b-parent` into `b-target`. A mirror stores
   its turn either way, so the axes tell what the rotations may not."
  [a a-parent a-target b b-parent b-target]
  (let [placed (fn [shape parent target]
                 (gmt/multiply (orientation-matrix target)
                               (gmt/inverse (orientation-matrix parent))
                               (orientation-matrix shape)))]
    (gmt/close? (placed a a-parent a-target) (placed b b-parent b-target))))

(defn turn-sign
  "-1 when `shape` is a mirror whose rotation is only the opposite of the
   turn its transform holds, so it stores a turn of its subtree subtracted;
   1 otherwise."
  [shape]
  (let [{:keys [a b]} (:transform shape)
        mirrored (not= (boolean (:flip-x shape)) (boolean (:flip-y shape)))
        sign     (if (:flip-x shape) -1 1)
        rotation (or (:rotation shape) 0)
        same?    #(mth/close? (mod (- %1 %2 -180) 360) 180 0.01)]
    (if (and mirrored (number? a) (number? b))
      (let [turn (mth/degrees (mth/atan2 (* sign b) (* sign a)))]
        (if (and (not (same? rotation turn)) (same? rotation (- turn))) -1 1))
      1)))

(defn- same-turn?
  [a b]
  (let [difference (mod (- a b) 360)]
    (or (< difference 0.01) (> difference 359.99))))

(defn rigid?
  "Whether the transform of `shape` turns and mirrors it without skew: its
   axes stay perpendicular."
  [shape]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        horizontal (gpt/point a b)
        vertical   (gpt/point c d)]
    (and (not (mth/almost-zero? (gpt/length horizontal)))
         (not (mth/almost-zero? (gpt/length vertical)))
         (mth/close? (gpt/dot (gpt/unit horizontal) (gpt/unit vertical)) 0 0.001))))

(defn decomposes?
  "Whether the rotation and flip flags of `shape` compose its transform,
   turning before or after the flip."
  [shape]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        mirrored (not= (boolean (:flip-x shape)) (boolean (:flip-y shape)))
        sign     (if (:flip-x shape) -1 1)
        turn     (mth/degrees (mth/atan2 (* sign b) (* sign a)))
        rotation (or (:rotation shape) 0)]
    (and (= mirrored (neg? (- (* a d) (* b c))))
         (or (same-turn? rotation turn)
             (and mirrored (same-turn? rotation (- turn)))
             ;; Both flags may record a half turn the rotation already counts.
             (and (:flip-x shape) (:flip-y shape) (same-turn? rotation (+ turn 180)))))))

(defn- orientation
  "R(`turn`)·Sx^`flip-x`·Sy^`flip-y` as `[angle mirror?]`, standing for
   R(angle)·Sx^mirror?: Sy is R(180)·Sx."
  [turn flip-x flip-y]
  [(cond-> turn flip-y (+ 180)) (not= (boolean flip-x) (boolean flip-y))])

(defn- compose
  [[a1 m1] [a2 m2]]
  [(+ a1 (if m1 (- a2) a2)) (not= m1 m2)])

(defn- inverse
  [[angle mirror?]]
  [(if mirror? angle (- angle)) mirror?])

(defn- shape-orientation
  [shape]
  (orientation (* (turn-sign shape) (or (:rotation shape) 0)) (:flip-x shape) (:flip-y shape)))

(defn reoriented-rotation
  "The rotation a shape with `values` (rotation and flips) stores once its
   parent goes from `source` to `target` and its flips become `flips'`.
   `sign` (`turn-sign`) tells which way its rotation stores its turn: a
   mirror keeps that way, and a shape that becomes one stores the opposite
   of its transform's turn."
  [values sign source target flips']
  (let [[angle _] (compose (compose (shape-orientation target) (inverse (shape-orientation source)))
                           (orientation (* sign (or (:rotation values) 0)) (:flip-x values) (:flip-y values)))
        turn      (cond-> angle (:flip-y flips') (- 180))
        mirror?   #(not= (boolean (:flip-x %)) (boolean (:flip-y %)))]
    (mod (if (and (mirror? flips') (or (= sign -1) (not (mirror? values)))) (- turn) turn) 360)))

(defn boxed
  "`shape` with the box of its selrect when it has none of its own, as a bool
   or a path keeps its geometry in its selrect and points only."
  [shape]
  (if (and (map? shape) (nil? (:width shape)) (:selrect shape))
    (merge shape (select-keys (:selrect shape) [:x :y :width :height]))
    shape))

(defn child-placement
  "Place a main child against its expected copy parent. Only lower-level
   constraint and matrix math is shared with production, not sync code."
  [main main-parent parent objects]
  (let [main-box (boxed main-parent)
        box      (boxed parent)
        resized (if (and (mth/close? (:width main-box) (:width box) 0.01)
                         (mth/close? (:height main-box) (:height box) 0.01))
                  objects
                  ;; a layout owns a filling or hugging parent's size and would drop
                  ;; the resize, so the parent takes it as a fixed size
                  (let [tree (gm/set-objects-modifiers
                              {(:id main-parent)
                               {:modifiers (ctm/change-size main-parent (:width box) (:height box))}}
                              (update objects (:id main-parent) assoc
                                      :layout-item-h-sizing :fix :layout-item-v-sizing :fix))]
                    (reduce-kv (fn [objects id {:keys [modifiers]}]
                                 (update objects id gsh/transform-shape modifiers)) objects tree)))
        main (get resized (:id main))
        main-parent (get resized (:id main-parent))
        center (gsh/shape->center main-parent)
        turn (gmt/multiply (orientation-matrix parent)
                           (gmt/inverse (orientation-matrix main-parent)))
        placed (if (gmt/unit? turn) main
                   (gsh/apply-transform main (gmt/transform-in center turn)))
        ;; Flip flags follow the parent's composition history. A single
        ;; large turn can toggle both flags where several small turns do not.
        flipped? (fn [key]
                   (odd? (count (filter identity [(get main key) (get main-parent key)
                                                  (get parent key)]))))]
    (let [flips {:flip-x (flipped? :flip-x) :flip-y (flipped? :flip-y)}]
      (-> placed
          (gsh/move (gpt/subtract (gsh/shape->center parent) center))
          (merge flips)
          (assoc :rotation (reoriented-rotation main (turn-sign main) main-parent parent flips))))))

(defn follow-parent-size
  "`child` resized through its own constraints as its parent goes from
   `parent` to `parent'`. A parent with a layout, of the same size or
   without a selrect leaves it as it is."
  [child parent parent']
  (let [sr  (:selrect parent)
        sr' (:selrect parent')
        sx  (when (and sr sr') (/ (:width sr') (max 0.01 (:width sr))))
        sy  (when (and sr sr') (/ (:height sr') (max 0.01 (:height sr))))]
    (if (or (nil? sx) (and (mth/close? sx 1) (mth/close? sy 1)))
      child
      (let [modifiers (ctm/resize (ctm/empty) (gpt/point sx sy) (first (:points parent))
                                  (:transform parent) (:transform-inverse parent))
            resized   (gsh/transform-shape parent modifiers)]
        (gsh/transform-shape child (gsh/calc-child-modifiers parent child modifiers false
                                                             (:points child) (:points parent)
                                                             (delay (:points resized))))))))

(defn ordered-corners
  "The corners `points` of a group or bool, which keeps them in any order,
   by their angle around their centre: it holds whatever the shape's size,
   turn or noise, where an order by position swaps the corners of a tiny or
   turned shape."
  [points]
  (when (seq points)
    (let [cx (/ (reduce + (map :x points)) (count points))
          cy (/ (reduce + (map :y points)) (count points))
          ;; angles to the ten-thousandth of a radian, rounded before they wrap,
          ;; so a corner level with the centre sorts first whichever side the
          ;; single precision noise of a bool puts it
          turn (Math/round (* 2e4 Math/PI))]
      (sort-by (fn [{:keys [x y]}]
                 (mod (Math/round (* 1e4 (Math/atan2 (- y cy) (- x cx)))) turn))
               points))))
