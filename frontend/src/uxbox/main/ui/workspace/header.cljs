;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.header
  (:require
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.config :as cfg]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.undo :as udu]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.ui.workspace.images :refer [import-image-modal]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.users :refer [user]]
   [uxbox.main.ui.workspace.clipboard]
   [uxbox.util.data :refer [index-of]]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt]))

;; --- Zoom Widget

(mf/defc zoom-widget
  [props]
  (let [zoom (mf/deref refs/selected-zoom)
        increase #(st/emit! (dw/increase-zoom))
        decrease #(st/emit! (dw/decrease-zoom))]
    [:ul.options-view
     [:li.zoom-input
      [:span.add-zoom {:on-click decrease} "-"]
      [:span {} (str (mth/round (* 100 zoom)) "%")]
      [:span.remove-zoom {:on-click increase} "+"]]]))

;; --- Header Component

(mf/defc header
  [{:keys [page flags] :as props}]
  (let [toggle #(st/emit! (dw/toggle-flag %))
        on-undo #(st/emit! (udu/undo))
        on-redo #(st/emit! (udu/redo))
        on-image #(modal/show! import-image-modal {})
        ;;on-download #(udl/open! :download)
        ]
    [:header#workspace-bar.workspace-bar
     [:div.main-icon
      [:a {:on-click #(st/emit! (rt/nav :dashboard/projects))} i/logo-icon]]
     [:div.project-tree-btn
      {:alt "Sitemap (Ctrl + Shift + M)"
       :class (when (contains? flags :sitemap) "selected")
       :on-click (partial toggle :sitemap)}
      i/project-tree
      [:span {} (:name page)]]
     [:div.workspace-options
      [:ul.options-btn
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
       [:li.tooltip.tooltip-bottom
        {:alt "Download (Ctrl + E)"
         ;; :on-click on-download
         }
        i/download]
       [:li.tooltip.tooltip-bottom
        {:alt "Image (Ctrl + I)"
         :on-click on-image}
        i/image]]
      [:ul.options-btn
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
      [:ul.options-btn
       [:li.tooltip.tooltip-bottom.view-mode
        {:alt "View mode (Ctrl + P)"
         :on-click #(st/emit! (dw/->OpenView (:id page)))
         }
        i/play]]
      [:& zoom-widget]]
     [:& user]]))
