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
   [uxbox.main.ui.components.file-uploader :refer [file-uploader]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.main.ui.icons :as i]))

;; --- Component: Left toolbar

(mf/defc left-toolbar
  [{:keys [page layout] :as props}]
  (let [file-input (mf/use-ref nil)
        selected-drawtool (mf/deref refs/selected-drawing-tool)
        select-drawtool #(st/emit! :interrupt
                                   (dw/select-for-drawing %))
        locale (i18n/use-locale)

        on-image #(dom/click (mf/ref-val file-input))

        on-uploaded
        (fn [{:keys [id name] :as image}]
          (let [shape {:name name
                       :metadata {:width (:width image)
                                  :height (:height image)
                                  :uri (:uri image)}}
                aspect-ratio (/ (:width image) (:height image))]
            (st/emit! (dw/create-and-add-shape :image shape aspect-ratio))))

        on-files-selected
        (fn [files]
          (run! #(st/emit! (dw/upload-media-object % on-uploaded)) files))]

    [:aside.left-toolbar
     [:div.left-toolbar-inside
      [:ul.left-toolbar-options
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.frame")
         :class (when (= selected-drawtool :frame) "selected")
         :on-click (partial select-drawtool :frame)}
        i/artboard]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.rect")
         :class (when (= selected-drawtool :rect) "selected")
         :on-click (partial select-drawtool :rect)}
        i/box]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.circle")
         :class (when (= selected-drawtool :circle) "selected")
         :on-click (partial select-drawtool :circle)}
        i/circle]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.text")
         :class (when (= selected-drawtool :text) "selected")
         :on-click (partial select-drawtool :text)}
        i/text]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.image")
         :on-click on-image}
        [:*
          i/image
          [:& file-uploader {:accept "image/jpeg,image/png,image/webp"
                             :multi true
                             :input-ref file-input
                             :on-selected on-files-selected}]]]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.curve")
         :class (when (= selected-drawtool :curve) "selected")
         :on-click (partial select-drawtool :curve)}
        i/pencil]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.path")
         :class (when (= selected-drawtool :path) "selected")
         :on-click (partial select-drawtool :path)}
        i/curve]]

      [:ul.left-toolbar-options.panels
       [:li.tooltip.tooltip-right
        {:alt "Layers"
         :class (when (contains? layout :layers) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flags :sitemap :layers))}
        i/layers]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.assets")
         :class (when (contains? layout :assets) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flags :assets))}
        i/icon-set]
       [:li.tooltip.tooltip-right
        {:alt "History"}
        i/undo-history]
       [:li.tooltip.tooltip-right
        {:alt (t locale "workspace.toolbar.color-palette")
         :class (when (contains? layout :colorpalette) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flags :colorpalette))}
        i/palette]]]]))
