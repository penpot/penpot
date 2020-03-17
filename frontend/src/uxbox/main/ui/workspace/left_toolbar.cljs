;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.left-toolbar
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.images :refer [import-image-modal]]
   [uxbox.builtins.icons :as i]))

;; --- Component: Left toolbar

(mf/defc left-toolbar
  [{:keys [page layout] :as props}]
  (let [selected-drawtool (mf/deref refs/selected-drawing-tool)
        select-drawtool #(st/emit! :interrupt
                                   (dw/select-for-drawing %))
        on-image #(modal/show! import-image-modal {})]
    [:div.left-toolbar
     [:div.left-toolbar-inside
      [:ul.left-toolbar-options
       [:li.tooltip.tooltip-right
        {:alt "Artboard"
         :class (when (= selected-drawtool :frame) "selected")
         :on-click (partial select-drawtool :frame)}
        i/artboard]
       [:li.tooltip.tooltip-right
        {:alt "Box"
         :class (when (= selected-drawtool :rect) "selected")
         :on-click (partial select-drawtool :rect)}
        i/box]
       [:li.tooltip.tooltip-right
        {:alt "Circle"
         :class (when (= selected-drawtool :circle) "selected")
         :on-click (partial select-drawtool :circle)}
        i/circle]
       [:li.tooltip.tooltip-right
        {:alt "Text"
         :class (when (= selected-drawtool :text) "selected")
         :on-click (partial select-drawtool :text)}
        i/text]
       [:li.tooltip.tooltip-right
        {:alt "Insert image"
         :on-click on-image}
        i/image]
       [:li.tooltip.tooltip-right
        {:alt "Pencil tool"
         :class (when (= selected-drawtool :path) "selected")
         :on-click (partial select-drawtool :path)}
        i/pencil]
       [:li.tooltip.tooltip-right
        {:alt "Curves tool"
         :class (when (= selected-drawtool :curve) "selected")
         :on-click (partial select-drawtool :curve)}
        i/curve]]

      [:ul.left-toolbar-options.panels
       [:li.tooltip.tooltip-right
        {:alt "Layers"
         :class (when (contains? layout :layers) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flag :layers))}
        i/layers]
       [:li.tooltip.tooltip-right
        {:alt "Libraries"}
        i/icon-set]
       [:li.tooltip.tooltip-right
        {:alt "History"}
        i/undo-history]
       [:li.tooltip.tooltip-right
        {:alt "Palette"
         :class (when (contains? layout :colorpalette) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flag :colorpalette))}
        i/palette]]]]))
