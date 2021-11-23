;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.align
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [clojure.spec.alpha :as s]))

;; --- Alignment

(s/def ::align-axis #{:hleft :hcenter :hright :vtop :vcenter :vbottom})

(declare calc-align-pos)

;; Duplicated from pages/helpers to remove cyclic dependencies
(defn- get-children [id objects]
  (let [shapes (vec (get-in objects [id :shapes]))]
    (if shapes
      (d/concat shapes (mapcat #(get-children % objects) shapes))
      [])))

(defn- recursive-move
  "Move the shape and all its recursive children."
  [shape dpoint objects]
  (let [children-ids (get-children (:id shape) objects)
        children (map #(get objects %) children-ids)]
    (map #(gsh/move % dpoint) (cons shape children))))

(defn align-to-rect
  "Move the shape so that it is aligned with the given rectangle
  in the given axis. Take account the form of the shape and the
  possible rotation. What is aligned is the rectangle that wraps
  the shape with the given rectangle. If the shape is a group,
  move also all of its recursive children."
  [shape rect axis objects]
  (let [wrapper-rect (gsh/selection-rect [shape])
        align-pos (calc-align-pos wrapper-rect rect axis)
        delta {:x (- (:x align-pos) (:x wrapper-rect))
               :y (- (:y align-pos) (:y wrapper-rect))}]
    (recursive-move shape delta objects)))

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

(s/def ::dist-axis #{:horizontal :vertical})

(defn distribute-space
  "Distribute equally the space between shapes in the given axis. If
  there is no space enough, it does nothing. It takes into account
  the form of the shape and the rotation, what is distributed is
  the wrapping rectangles of the shapes. If any shape is a group,
  move also all of its recursive children."
  [shapes axis objects]
  (let [coord (if (= axis :horizontal) :x :y)
        other-coord (if (= axis :horizontal) :y :x)
        size (if (= axis :horizontal) :width :height)
        ; The rectangle that wraps the whole selection
        wrapper-rect (gsh/selection-rect shapes)
        ; Sort shapes by the center point in the given axis
        sorted-shapes (sort-by #(coord (gsh/center-shape %)) shapes)
        ; Each shape wrapped in its own rectangle
        wrapped-shapes (map #(gsh/selection-rect [%]) sorted-shapes)
        ; The total space between shapes
        space (reduce - (size wrapper-rect) (map size wrapped-shapes))]

    (if (<= space 0)
      shapes
      (let [unit-space (/ space (- (count wrapped-shapes) 1))
            ; Calculate the distance we need to move each shape.
            ; The new position of each one is the position of the
            ; previous one plus its size plus the unit space.
            deltas (loop [shapes' wrapped-shapes
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

        (mapcat #(recursive-move %1 {coord %2 other-coord 0} objects)
                sorted-shapes deltas)))))

;; Adjust to viewport

(defn adjust-to-viewport
  ([viewport srect] (adjust-to-viewport viewport srect nil))
  ([viewport srect {:keys [padding] :or {padding 0}}]
   (let [gprop (/ (:width viewport) (:height viewport))
         srect (-> srect
                   (update :x #(- % padding))
                   (update :y #(- % padding))
                   (update :width #(+ % padding padding))
                   (update :height #(+ % padding padding)))
         width (:width srect)
         height (:height srect)
         lprop (/ width height)]
     (cond
      (> gprop lprop)
      (let [width'  (* (/ width lprop) gprop)
            padding (/ (- width' width) 2)]
        (-> srect
            (update :x #(- % padding))
            (assoc :width width')))

      (< gprop lprop)
      (let [height' (/ (* height lprop) gprop)
            padding (/ (- height' height) 2)]
        (-> srect
            (update :y #(- % padding))
            (assoc :height height')))

      :else srect))))
