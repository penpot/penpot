;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.left-toolbar
  (:require
   [rumext.alpha :as mf]
   [app.common.media :as cm]
   [app.main.refs :as refs]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.util.object :as obj]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.main.ui.icons :as i]))

(mf/defc image-upload
  {::mf/wrap [mf/memo]}
  []
  (let [ref  (mf/use-ref nil)
        file (mf/deref refs/workspace-file)

        on-click
        (mf/use-callback #(dom/click (mf/ref-val ref)))

        on-uploaded
        (mf/use-callback
         (fn [{:keys [id name] :as image}]
           (let [shape {:name name
                        :width  (:width image)
                        :height (:height image)
                        :metadata {:width  (:width image)
                                   :height (:height image)
                                   :id     (:id image)
                                   :path   (:path image)}}
                 aspect-ratio (/ (:width image) (:height image))]
             (st/emit! (dw/create-and-add-shape :image 0 0 shape)))))

        on-files-selected
        (mf/use-callback
         (mf/deps file)
         (fn [js-files]
           (st/emit! (dw/upload-media-objects
                      (with-meta {:file-id (:id file)
                                  :local? true
                                  :js-files js-files}
                        {:on-success on-uploaded})))))]

       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.image")
         :on-click on-click}
        [:*
         i/image
         [:& file-uploader {:input-id "image-upload"
                            :accept cm/str-media-types
                            :multi true
                            :input-ref ref
                            :on-selected on-files-selected}]]]))

(mf/defc left-toolbar
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [layout            (obj/get props "layout")
        selected-drawtool (mf/deref refs/selected-drawing-tool)
        select-drawtool   #(st/emit! :interrupt (dw/select-for-drawing %))
        edition           (mf/deref refs/selected-edition)]
    [:aside.left-toolbar
     [:div.left-toolbar-inside
      [:ul.left-toolbar-options
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.move")
         :class (when (and (nil? selected-drawtool)
                           (not edition)) "selected")
         :on-click (st/emitf :interrupt)}
        i/pointer-inner]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.frame")
         :class (when (= selected-drawtool :frame) "selected")
         :on-click (partial select-drawtool :frame)}
        i/artboard]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.rect")
         :class (when (= selected-drawtool :rect) "selected")
         :on-click (partial select-drawtool :rect)}
        i/box]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.ellipse")
         :class (when (= selected-drawtool :circle) "selected")
         :on-click (partial select-drawtool :circle)}
        i/circle]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.text")
         :class (when (= selected-drawtool :text) "selected")
         :on-click (partial select-drawtool :text)}
        i/text]

       [:& image-upload]

       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.curve")
         :class (when (= selected-drawtool :curve) "selected")
         :on-click (partial select-drawtool :curve)}
        i/pencil]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.path")
         :class (when (= selected-drawtool :path) "selected")
         :on-click (partial select-drawtool :path)}
        i/pen]

       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.comments")
         :class (when (= selected-drawtool :comments) "selected")
         :on-click (partial select-drawtool :comments)}
        i/chat]]

      [:ul.left-toolbar-options.panels
       [:li.tooltip.tooltip-right
        {:alt "Layers"
         :class (when (contains? layout :layers) "selected")
         :on-click (st/emitf (dw/go-to-layout :layers))}
        i/layers]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.assets")
         :class (when (contains? layout :assets) "selected")
         :on-click (st/emitf (dw/go-to-layout :assets))}
        i/library]
       [:li.tooltip.tooltip-right
        {:alt "History"
         :class (when (contains? layout :document-history) "selected")
         :on-click (st/emitf (dw/go-to-layout :document-history))}
        i/undo-history]
       [:li.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.color-palette")
         :class (when (contains? layout :colorpalette) "selected")
         :on-click (st/emitf (dw/toggle-layout-flags :colorpalette))}
        i/palette]]]]))
