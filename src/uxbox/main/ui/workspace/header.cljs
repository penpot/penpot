;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [uxbox.common.router :as r]
            [uxbox.common.rstore :as rs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.history :as udh]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.workspace.clipboard]
            [uxbox.main.ui.workspace.settings]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.users :as ui.u]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.common.geom.point :as gpt]
            [uxbox.util.math :as mth]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinates Debug
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coordenates-render
  [own]
  (let [zoom (rum/react wb/zoom-l)
        coords (some-> (rum/react wb/mouse-canvas-a)
                       (gpt/divide zoom)
                       (gpt/round 1))
        increase #(rs/emit! (dw/increase-zoom))
        decrease #(rs/emit! (dw/decrease-zoom))]
    (html
     [:ul.options-view
      [:li.coordinates {:alt "x"}
       (str "X: " (:x coords "-"))]
      [:li.coordinates {:alt "y"}
       (str "Y: " (:y coords "-"))]
      [:li.zoom-input
       [:span.add-zoom {:on-click increase} "+"]
       [:span (str (mth/round (* 100 zoom)) "%")]
       [:span.remove-zoom {:on-click decrease} "-"]]])))

(def coordinates
  (mx/component
   {:render coordenates-render
    :name "coordenates"
    :mixins [rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Header
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-download-clicked
  [event page]
  (udl/open! :download)
  #_(let [content (.-innerHTML (.getElementById js/document "page-layout"))
        width (:width page)
        height (:height page)
        html (str "<svg width='" width  "' height='" height  "'>" content "</svg>")
        data (js/Blob. #js [html] #js {:type "application/octet-stream"})
        url (.createObjectURL (.-URL js/window) data)]
    (set! (.-href (.-currentTarget event)) url)))

(defn header-render
  [own]
  (let [page (rum/react wb/page-l)
        flags (rum/react wb/flags-l)
        toggle #(rs/emit! (dw/toggle-flag %))
        on-undo #(rs/emit! (udh/backwards-to-previous-version))
        on-redo #(rs/emit! (udh/forward-to-next-version))
        on-image #(udl/open! :new-image)
        ;; TODO: temporary
        open-confirm-dialog #(udl/open! :confirm)]
    (html
     [:header#workspace-bar.workspace-bar
      [:div.main-icon
       (nav/link (r/route-for :dashboard/projects) i/logo-icon)]
      [:div.project-tree-btn
       {:alt "Sitemap (Ctrl + Shift + M)"
        :class (when (contains? flags :sitemap) "selected")
        :on-click (partial toggle :sitemap)}
       i/project-tree
       [:span (:name page)]]
      [:div.workspace-options
       [:ul.options-btn
        [:li.tooltip.tooltip-bottom
         {:alt "Shapes (Ctrl + Shift + S)"
          :class (when (contains? flags :drawtools) "selected")
          :on-click (partial toggle :drawtools)}
         i/shapes]
        [:li.tooltip.tooltip-bottom
         {:alt "Color Palette (---)"
          :class (when (contains? flags :colorpalette) "selected")
          :on-click (partial toggle :colorpalette)}
         i/palette]
        [:li.tooltip.tooltip-bottom
         {:alt "Icons (Ctrl + Shift + I)"
          :class (when (contains? flags :icons) "selected")
          :on-click (partial toggle :icons)}
         i/icon-set]
        [:li.tooltip.tooltip-bottom
         {:alt "Layers (Ctrl + Shift + L)"
          :class (when (contains? flags :layers) "selected")
          :on-click (partial toggle :layers)}
         i/layers]
        [:li.tooltip.tooltip-bottom
         {:alt "Element options (Ctrl + Shift + O)"
          :class (when (contains? flags :element-options) "selected")
          :on-click (partial toggle :element-options)}
         i/options]
        [:li.tooltip.tooltip-bottom
         {:alt "History (Ctrl + Shift + H)"
          :class (when (contains? flags :document-history) "selected")
          :on-click (partial toggle :document-history)}
         i/undo-history]]
       [:ul.options-btn
        [:li.tooltip.tooltip-bottom
         {:alt "Undo (Ctrl + Z)"
          :on-click on-undo}
         i/undo]
        [:li.tooltip.tooltip-bottom
         {:alt "Redo (Ctrl + Shift + Z)"
         :on-click on-redo}
         i/redo]]
       [:ul.options-btn
        ;; TODO: refactor
        [:li.tooltip.tooltip-bottom
         {:alt "Export (Ctrl + E)"
          :on-click on-download-clicked}
         i/export]
        [:li.tooltip.tooltip-bottom
         {:alt "Image (Ctrl + I)"
          :on-click on-image}
         i/image]]
       [:ul.options-btn
        [:li.tooltip.tooltip-bottom
         {:alt "Ruler (Ctrl + R)"
          :class (when (contains? flags :ruler) "selected")
          :on-click (partial toggle :ruler)}
         i/ruler]
        [:li.tooltip.tooltip-bottom
         {:alt "Grid (Ctrl + G)"
          :class (when (contains? flags :grid) "selected")
          :on-click (partial toggle :grid)}
         i/grid]
        [:li.tooltip.tooltip-bottom
         {:alt "Align (Ctrl + A)"}
         i/alignment]]
       [:ul.options-btn
        [:li.tooltip.tooltip-bottom
         {:alt "Multi-canvas (Ctrl + M)"}
         i/multicanvas]]
       (coordinates)]
      (ui.u/user)])))

(def header
  (mx/component
   {:render header-render
    :name "workspace-header"
    :mixins [rum/reactive]}))
