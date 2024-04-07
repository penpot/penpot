;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.drawarea
  "Drawing components."
  (:require
   [app.common.math :as mth]
   [app.common.types.shape :as cts]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.main.ui.workspace.shapes :as shapes]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor]]
   [rumext.v2 :as mf]))

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [shape zoom tool] :as props}]

  ;; Prevent rendering something that it's not a shape.
  (when (cts/shape? shape)
    [:g.draw-area
     [:g {:style {:pointer-events "none"}}
      [:& shapes/shape-wrapper {:shape shape}]]

     (case tool
       :path      [:& path-editor {:shape shape :zoom zoom}]
       :curve     [:& path-shape {:shape shape :zoom zoom}]
       #_:default [:& generic-draw-area {:shape shape :zoom zoom}])]))

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    (when (and x y
               (not (mth/nan? x))
               (not (mth/nan? y)))

      [:rect.main {:x x :y y
                   :width width
                   :height height
                   :style {:stroke "var(--color-accent-tertiary)"
                           :fill "none"
                           :stroke-width (/ 1 zoom)}}])))

