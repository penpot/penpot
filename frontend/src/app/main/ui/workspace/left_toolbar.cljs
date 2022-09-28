;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.left-toolbar
  (:require
   [app.common.geom.point :as gpt]
   [app.common.media :as cm]
   [app.main.data.events :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))


(mf/defc image-upload
  {::mf/wrap [mf/memo]}
  []
  (let [ref  (mf/use-ref nil)
        file (mf/deref refs/workspace-file)

        on-click
        (mf/use-callback #(dom/click (mf/ref-val ref)))

        on-files-selected
        (mf/use-callback
         (mf/deps file)
         (fn [blobs]
           ;; We don't want to add a ref because that redraws the component
           ;; for everychange. Better direct access on the callback.
           (let [vbox   (deref refs/vbox)
                 x      (+ (:x vbox) (/ (:width vbox) 2))
                 y      (+ (:y vbox) (/ (:height vbox) 2))
                 params {:file-id (:id file)
                         :blobs (seq blobs)
                         :position (gpt/point x y)}]
             (st/emit! (dwm/upload-media-workspace params)))))]

    [:li
     [:button.tooltip.tooltip-right
      {:alt (tr "workspace.toolbar.image" (sc/get-tooltip :insert-image))
       :on-click on-click}
      [:*
       i/image
       [:& file-uploader {:input-id "image-upload"
                          :accept cm/str-image-types
                          :multi true
                          :ref ref
                          :on-selected on-files-selected}]]]]))

(mf/defc left-toolbar
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [layout            (obj/get props "layout")
        selected-drawtool (mf/deref refs/selected-drawing-tool)
        select-drawtool   #(st/emit! :interrupt (dw/select-for-drawing %))
        edition           (mf/deref refs/selected-edition)]
    [:aside.left-toolbar
     [:ul.left-toolbar-options
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.move"  (sc/get-tooltip :move))
         :class (when (and (nil? selected-drawtool)
                           (not edition)) "selected")
         :on-click #(st/emit! :interrupt)}
        i/pointer-inner]]
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.frame" (sc/get-tooltip :draw-frame))
         :class (when (= selected-drawtool :frame) "selected")
         :on-click (partial select-drawtool :frame)
         :data-test "artboard-btn"}
        i/artboard]]
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.rect" (sc/get-tooltip :draw-rect))
         :class (when (= selected-drawtool :rect) "selected")
         :on-click (partial select-drawtool :rect)
         :data-test "rect-btn"}
        i/box]]
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.ellipse" (sc/get-tooltip :draw-ellipse))
         :class (when (= selected-drawtool :circle) "selected")
         :on-click (partial select-drawtool :circle)
         :data-test "ellipse-btn"}
        i/circle]]
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.text" (sc/get-tooltip :draw-text))
         :class (when (= selected-drawtool :text) "selected")
         :on-click (partial select-drawtool :text)}
        i/text]]

      [:& image-upload]

      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.curve" (sc/get-tooltip :draw-curve))
         :class (when (= selected-drawtool :curve) "selected")
         :on-click (partial select-drawtool :curve)
         :data-test "curve-btn"}
        i/pencil]]
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.path" (sc/get-tooltip :draw-path))
         :class (when (= selected-drawtool :path) "selected")
         :on-click (partial select-drawtool :path)
         :data-test "path-btn"}
        i/pen]]

      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.comments" (sc/get-tooltip :add-comment))
         :class (when (= selected-drawtool :comments) "selected")
         :on-click (partial select-drawtool :comments)}
        i/chat]]]

     [:ul.left-toolbar-options.panels
      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
         :class (when (contains? layout :textpalette) "selected")
         :on-click (fn []
                     (r/set-resize-type! :bottom)
                     (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
                     (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :colorpalette)
                                                 (-> (dw/toggle-layout-flag :textpalette)
                                                     (vary-meta assoc ::ev/origin "workspace-left-toolbar")))))}
        "Ag"]]

      [:li
       [:button.tooltip.tooltip-right
        {:alt (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
         :class (when (contains? layout :colorpalette) "selected")
         :on-click (fn []
                     (r/set-resize-type! :bottom)
                     (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
                     (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :textpalette)
                                                 (-> (dw/toggle-layout-flag :colorpalette)
                                                     (vary-meta assoc ::ev/origin "workspace-left-toolbar")))))}
        i/palette]]
      [:li
       [:button.tooltip.tooltip-right.separator
        {:alt (tr "workspace.toolbar.shortcuts" (sc/get-tooltip :show-shortcuts))
         :class (when (contains? layout :shortcuts) "selected")
         :on-click (fn []
                     (let [is-sidebar-closed? (contains? layout :collapse-left-sidebar)]
                       (ts/schedule 300 #(st/emit! (when is-sidebar-closed? (dw/toggle-layout-flag :collapse-left-sidebar))
                                                   (-> (dw/toggle-layout-flag :shortcuts)
                                                       (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))))}
        i/shortcut]]]]))
