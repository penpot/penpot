;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.geom.image-crop
  "Geometry of the image crop (resize with the mod key on a shape with an
   image fill). The shape is a window over a picture that keeps its size
   and position; the window never flips."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.math :as mth]))

(defn crop-axis
  "Resize one axis of the crop window. `size` is the current window size,
   `origin` the fixed point relative to the window start and `scale` the
   resize factor, negative when the dragged edge crosses the fixed point.
   Returns `[offset size]`: the new window start relative to the old one,
   and the new size, always positive."
  [size origin scale]
  (let [start (* origin (- 1 scale))
        end   (+ origin (* (- size origin) scale))]
    [(mth/min start end) (mth/abs (- end start))]))

(defn update-image-transform
  "Return the image fill transform that keeps the picture in place when
   the window of `width` x `height` moves by `dx`, `dy` and gets a size of
   `width'` x `height'`. The transform values are relative to the window
   size, and a nil transform means the picture fills the window."
  [transform width height dx dy width' height']
  (let [x (get transform :x 0.0)
        y (get transform :y 0.0)
        w (get transform :width 1.0)
        h (get transform :height 1.0)]
    {:x      (/ (- (* x width) dx) width')
     :y      (/ (- (* y height) dy) height')
     :width  (/ (* w width) width')
     :height (/ (* h height) height')}))

(defn preview-image-matrix
  "Return the matrix that takes the picture of a `width` x `height` window
   from its box under `transform` to its box under `transform'`, both in
   the coordinates of the window before the resize. While dragging, the
   SVG preview resizes the whole shape with one matrix; this one, applied
   to the picture first, undoes that resize so the picture stays in place."
  [transform transform' width height]
  (let [x  (* (get transform :x 0.0) width)
        y  (* (get transform :y 0.0) height)
        x' (* (get transform' :x 0.0) width)
        y' (* (get transform' :y 0.0) height)
        sx (/ (get transform' :width 1.0) (get transform :width 1.0))
        sy (/ (get transform' :height 1.0) (get transform :height 1.0))]
    (gmt/matrix sx 0 0 sy (- x' (* x sx)) (- y' (* y sy)))))
