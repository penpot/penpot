;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.drawarea
  "Drawing components."
  (:require
   [rumext.alpha :as mf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dd]
   [app.main.store :as st]
   [app.main.ui.workspace.shapes :as shapes]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor]]
   [app.common.geom.shapes :as gsh]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]))

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [shape zoom tool] :as props}]

  [:g.draw-area
   [:g {:style {:pointer-events "none"}}
    [:& shapes/shape-wrapper {:shape shape}]]

   (case tool
     :path      [:& path-editor {:shape shape :zoom zoom}]
     :curve     [:& path-shape {:shape shape :zoom zoom}]
     #_:default [:& generic-draw-area {:shape shape :zoom zoom}])])

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x y width height]} (:selrect (gsh/transform-shape shape))]
    (when (and x y
               (not (d/nan? x))
               (not (d/nan? y)))

      [:rect.main {:x x :y y
                   :width width
                   :height height
                   :style {:stroke "#1FDEA7"
                           :fill "transparent"
                           :stroke-width (/ 1 zoom)}}])))

