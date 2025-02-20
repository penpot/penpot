;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.selection-feedback
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.measurements :refer [size-display measurement]]
   [rumext.v2 :as mf]))

;; ------------------------------------------------
;; CONSTANTS
;; ------------------------------------------------

(def select-color "var(--color-accent-tertiary)")
(def selection-rect-width 1)
(def select-guide-width 1)
(def select-guide-dasharray 5)

(defn resolve-shapes
  [objects ids]
  (let [resolve-shape (d/getf objects)]
    (into [] (keep resolve-shape) ids)))

;; ------------------------------------------------
;; HELPERS
;; ------------------------------------------------

(defn frame->bounds [frame]
  {:x 0
   :y 0
   :width (:width frame)
   :height (:height frame)})

;; ------------------------------------------------
;; COMPONENTS
;; ------------------------------------------------

(mf/defc selection-rect [{:keys [selrect zoom]}]
  (let [{:keys [x y width height]} selrect
        selection-rect-width (/ selection-rect-width zoom)]
    [:g.selection-rect
     [:rect {:x x
             :y y
             :width width
             :height height
             :style {:fill "none"
                     :stroke select-color
                     :stroke-width selection-rect-width}}]]))

(mf/defc selection-feedback
  [{:keys [frame local objects size]}]
  (let [{:keys [hover selected zoom]} local

        shapes          (resolve-shapes objects [hover])
        hover-shape     (or (first shapes) frame)
        selected-shapes (resolve-shapes objects selected)
        selrect         (gsh/shapes->rect selected-shapes)]

    (when (d/not-empty? selected-shapes)
      [:g.selection-feedback {:pointer-events "none"}
       [:g.selected-shapes
        [:& selection-rect {:selrect selrect :zoom zoom}]
        [:& size-display {:selrect selrect :zoom zoom}]]

       [:& measurement {:bounds (assoc size :x 0 :y 0)
                        :selected-shapes selected-shapes
                        :hover-shape hover-shape
                        :zoom zoom}]])))
