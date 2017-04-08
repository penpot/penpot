;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.header
  (:require [beicon.core :as rx]
            [uxbox.config :as cfg]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.history :as udh]
            [uxbox.main.data.undo :as udu]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.workspace.clipboard]
            [uxbox.main.ui.users :as ui.u]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.router :as r]
            [uxbox.util.data :refer [index-of]]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.math :as mth]
            [rumext.core :as mx :include-macros true]))

;; --- Zoom Widget

(mx/defc zoom-widget
  {:mixins [mx/reactive mx/static]}
  []
  (let [zoom (mx/react refs/selected-zoom)
        increase #(st/emit! (dw/increase-zoom))
        decrease #(st/emit! (dw/decrease-zoom))]
    [:ul.options-view {}
     [:li.zoom-input {}
      [:span.add-zoom {:on-click decrease} "-"]
      [:span {} (str (mth/round (* 100 zoom)) "%")]
      [:span.remove-zoom {:on-click increase} "+"]]]))

;; --- Header Component

(defn on-view-clicked
  [event project page]
  (let [token (:share-token project)
        pages (deref refs/selected-project-pages)
        index (index-of pages page)
        rval (rand-int 1000000)
        url (str cfg/viewurl "?v=" rval "#/" token "/" index)]
    (st/emit! (udp/persist-page (:id page) #(js/open url "new tab" "")))))

(mx/defc header
  {:mixins [mx/static mx/reactive]}
  []
  (let [project (mx/react refs/selected-project)
        page (mx/react refs/selected-page)
        flags (mx/react refs/flags)
        toggle #(st/emit! (dw/toggle-flag %))
        on-undo #(st/emit! (udu/undo))
        on-redo #(st/emit! (udu/redo))
        on-image #(udl/open! :import-image)
        on-download #(udl/open! :download)]
    [:header#workspace-bar.workspace-bar {}
     [:div.main-icon {}
      (nav/link (r/route-for :dashboard/projects) i/logo-icon)]
     [:div.project-tree-btn
      {:alt "Sitemap (Ctrl + Shift + M)"
       :class (when (contains? flags :sitemap) "selected")
       :on-click (partial toggle :sitemap)}
      i/project-tree
      [:span {} (:name page)]]
     [:div.workspace-options {}
      [:ul.options-btn {}
       [:li.tooltip.tooltip-bottom
        {:alt "Draw tools (Ctrl + Shift + S)"
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
      [:ul.options-btn {}
       [:li.tooltip.tooltip-bottom
        {:alt "Undo (Ctrl + Z)"
         :on-click on-undo}
        i/undo]
       [:li.tooltip.tooltip-bottom
        {:alt "Redo (Ctrl + Shift + Z)"
         :on-click on-redo}
        i/redo]]
      [:ul.options-btn {}
       [:li.tooltip.tooltip-bottom
        {:alt "Download (Ctrl + E)"
         :on-click on-download}
        i/download]
       [:li.tooltip.tooltip-bottom
        {:alt "Image (Ctrl + I)"
         :on-click on-image}
        i/image]]
      [:ul.options-btn {}
       [:li.tooltip.tooltip-bottom
        {:alt "Rules"
         :class (when (contains? flags :rules) "selected")
         :on-click (partial toggle :rules)}
        i/ruler]
       [:li.tooltip.tooltip-bottom
        {:alt "Grid (Ctrl + G)"
         :class (when (contains? flags :grid) "selected")
         :on-click (partial toggle :grid)}
        i/grid]
       [:li.tooltip.tooltip-bottom
        {:alt "Snap to grid"
         :class (when (contains? flags :grid-snap) "selected")
         :on-click (partial toggle :grid-snap)}
        i/grid-snap]]
       ;; [:li.tooltip.tooltip-bottom
        ;; {:alt "Align (Ctrl + A)"}
        ;; i/alignment]]
      [:ul.options-btn {}
       [:li.tooltip.tooltip-bottom.view-mode
        {:alt "View mode (Ctrl + P)"
         :on-click #(on-view-clicked % project page)}
        i/play]]
      (zoom-widget)]
     (ui.u/user)]))
