;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.align
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.points :as gpo]))

;; --- Alignment

(def valid-align-axis
  #{:hleft :hcenter :hright :vtop :vcenter :vbottom})

(declare calc-align-pos)

(defn align-to-rect
  "Move the shape so that it is aligned with the given rectangle
  in the given axis. Take account the form of the shape and the
  possible rotation. What is aligned is the rectangle that wraps
  the shape with the given rectangle. If the shape is a group,
  move also all of its recursive children."
  [shape rect axis]
  (let [wrapper-rect (gsh/shapes->rect [shape])
        align-pos (calc-align-pos wrapper-rect rect axis)
        delta (gpt/point (- (:x align-pos) (:x wrapper-rect))
                         (- (:y align-pos) (:y wrapper-rect)))]
    (gsh/move shape delta)))

(defn align-to-parent
  "Does the same calc as align-to-rect but relative to a parent shape."
  [shape parent axis]
  (let [parent-bounds (:points parent)
        wrapper-rect
        (-> (gsh/transform-points (:points shape) (gsh/shape->center parent) (:transform-inverse parent))
            (grc/points->rect))

        align-pos (calc-align-pos wrapper-rect (:selrect parent) axis)

        xv   #(gpo/start-hv parent-bounds %)
        yv   #(gpo/start-vv parent-bounds %)

        delta (-> (xv (- (:x align-pos) (:x wrapper-rect)))
                  (gpt/add (yv (- (:y align-pos) (:y wrapper-rect)))))]
    (gsh/move shape delta)))

(defn calc-align-pos
  [wrapper-rect rect axis]
  (case axis
    :hleft (let [left (:x rect)]
             {:x left
              :y (:y wrapper-rect)})

    :hcenter (let [center (+ (:x rect) (/ (:width rect) 2))]
               {:x (- center (/ (:width wrapper-rect) 2))
                :y (:y wrapper-rect)})

    :hright (let [right (+ (:x rect) (:width rect))]
              {:x (- right (:width wrapper-rect))
               :y (:y wrapper-rect)})

    :vtop (let [top (:y rect)]
            {:x (:x wrapper-rect)
             :y top})

    :vcenter (let [center (+ (:y rect) (/ (:height rect) 2))]
               {:x (:x wrapper-rect)
                :y (- center (/ (:height wrapper-rect) 2))})

    :vbottom (let [bottom (+ (:y rect) (:height rect))]
               {:x (:x wrapper-rect)
                :y (- bottom (:height wrapper-rect))})))

;; --- Distribute

(def valid-dist-axis
  #{:horizontal :vertical})

(defn distribute-space
  "Distribute equally the space between shapes in the given axis.
  It takes into account the form of the shape and the rotation,
  what is distributed is the wrapping rectangles of the shapes.
  If any shape is a group, move also all of its recursive children."
  [shapes axis]
  (let [coord (if (= axis :horizontal) :x :y)
        other-coord (if (= axis :horizontal) :y :x)
        size (if (= axis :horizontal) :width :height)
        ;; The rectangle that wraps the whole selection
        wrapper-rect (gsh/shapes->rect shapes)
        ;; Sort shapes by the center point in the given axis
        sorted-shapes (sort-by #(coord (gsh/shape->center %)) shapes)
        ;; Each shape wrapped in its own rectangle
        wrapped-shapes (map #(gsh/shapes->rect [%]) sorted-shapes)
        ;; The total space between shapes
        space (reduce - (size wrapper-rect) (map size wrapped-shapes))
        unit-space (/ space (- (count wrapped-shapes) 1))

        ;; Calculate the distance we need to move each shape.
        ;; The new position of each one is the position of the
        ;; previous one plus its size plus the unit space.
        deltas
        (loop [shapes' wrapped-shapes
               start-pos (coord wrapper-rect)
               deltas []]

          (let [first-shape (first shapes')
                delta (- start-pos (coord first-shape))
                new-pos (+ start-pos (size first-shape) unit-space)]

            (if (= (count shapes') 1)
              (conj deltas delta)
              (recur (rest shapes')
                     new-pos
                     (conj deltas delta)))))]

    (map #(gsh/move %1 (assoc (gpt/point) coord %2 other-coord 0))
         sorted-shapes deltas)))

;; Adjust to viewport

(defn adjust-to-viewport
  ([viewport srect] (adjust-to-viewport viewport srect nil))
  ([viewport srect {:keys [padding min-zoom] :or {padding 0 min-zoom nil}}]
   (let [gprop  (/ (:width viewport)
                   (:height viewport))
         srect-padded (-> srect
                          (update :x #(- % padding))
                          (update :y #(- % padding))
                          (update :width #(+ % padding padding))
                          (update :height #(+ % padding padding)))
         width  (:width srect-padded)
         height (:height srect-padded)
         lprop  (/ width height)
         adjusted-rect
         (cond
           (> gprop lprop)
           (let [width'  (* (/ width lprop) gprop)
                 padding (/ (- width' width) 2)]
             (-> srect-padded
                 (update :x #(- % padding))
                 (assoc :width width')
                 (grc/update-rect :position)))

           (< gprop lprop)
           (let [height' (/ (* height lprop) gprop)
                 padding (/ (- height' height) 2)]
             (-> srect-padded
                 (update :y #(- % padding))
                 (assoc :height height')
                 (grc/update-rect :position)))

           :else
           (grc/update-rect srect-padded :position))]
     ;; If min-zoom is specified and the resulting zoom would be below it,
     ;; return a rect with the original top-left corner centered in the viewport
     ;; instead of using the aspect-ratio-adjusted rect (which can push coords
     ;; extremely far with extreme aspect ratios).
     (if (and (some? min-zoom)
              (< (/ (:width viewport) (:width adjusted-rect)) min-zoom))
       (let [anchor-x (:x srect)
             anchor-y (:y srect)
             vbox-width (/ (:width viewport) min-zoom)
             vbox-height (/ (:height viewport) min-zoom)]
         (-> adjusted-rect
             (assoc :x (- anchor-x (/ vbox-width 2))
                    :y (- anchor-y (/ vbox-height 2))
                    :width vbox-width
                    :height vbox-height)
             (grc/update-rect :position)))
       adjusted-rect))))
