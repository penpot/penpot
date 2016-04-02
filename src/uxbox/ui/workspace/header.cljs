;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.workspace.clipboard]
            [uxbox.ui.workspace.settings]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.users :as ui.u]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.util.math :as mth]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coordinates Debug
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coordenates-render
  [own]
  (when-let [{:keys [x y]} (rum/react wb/mouse-canvas-a)]
    (html
     [:ul.options-btn
      [:li.tooltip.tooltip-bottom {:alt "x"} x]
      [:li.tooltip.tooltip-bottom {:alt "y"} y]])))

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
  (let [content (.-innerHTML (.getElementById js/document "page-layout"))
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
        ;; TODO: temporary
        open-clipboard-dialog #(lightbox/open! :clipboard)
        open-confirm-dialog #(lightbox/open! :confirm)]
    (html
     [:header#workspace-bar.workspace-bar
      [:div.main-icon
       (nav/link (r/route-for :dashboard/projects) i/logo-icon)]
      [:div.project-tree-btn
       {:on-click (partial toggle :sitemap)}
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
          :on-click open-clipboard-dialog}
         i/undo]
        [:li.tooltip.tooltip-bottom
         {:alt "Redo (Ctrl + Shift + Z)"
         :on-click open-confirm-dialog}
         i/redo]]
       [:ul.options-btn
        ;; TODO: refactor
        [:li.tooltip.tooltip-bottom
         {:alt "Export (Ctrl + E)"}
         ;; page-title
         [:a {:download (str (:name page) ".svg")
              :href "#" :on-click on-download-clicked}
          i/export]]
        [:li.tooltip.tooltip-bottom
         {:alt "Image (Ctrl + I)"}
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
