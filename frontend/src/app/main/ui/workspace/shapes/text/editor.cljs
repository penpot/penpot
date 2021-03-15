;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text.editor
  (:require
   ["draft-js" :as draft]
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.cursors :as cur]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf])
  (:import
   goog.events.EventType
   goog.events.KeyCodes))

;; --- Data functions

;; TODO: why we need this?
;; (defn- fix-gradients
;;   "Fix for the gradient types that need to be keywords"
;;   [content]
;;   (let [fix-node
;;         (fn [node]
;;           (d/update-in-when node [:fill-color-gradient :type] keyword))]
;;     (txt/map-node fix-node content)))

;; --- Text Editor Rendering

(mf/defc block-component
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")
        bprops   (obj/get props "blockProps")
        style    (sts/generate-paragraph-styles (obj/get bprops "shape")
                                                (obj/get bprops "data"))]

    [:div {:style style :dir "auto"}
     [:> draft/EditorBlock props]]))

(defn render-block
  [block shape]
  (let [type (ted/get-editor-block-type block)]
    (case type
      "unstyled"
      #js {:editable true
           :component block-component
           :props #js {:data (ted/get-editor-block-data block)
                       :shape shape}}
      nil)))

(def empty-editor-state
  (ted/create-editor-state))

(mf/defc text-shape-edit-html
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [{:keys [id x y width height grow-type content] :as shape} (obj/get props "shape")

        zoom          (mf/deref refs/selected-zoom)
        state-map     (mf/deref refs/workspace-editor-state)
        state         (get state-map id empty-editor-state)

        self-ref      (mf/use-ref)

        on-click-outside
        (fn [event]
          (let [target     (dom/get-target event)
                options    (dom/get-element-by-class "element-options")
                assets     (dom/get-element-by-class "assets-bar")
                cpicker    (dom/get-element-by-class "colorpicker-tooltip")
                palette    (dom/get-element-by-class "color-palette")

                self       (mf/ref-val self-ref)]
            (if (or (and options (.contains options target))
                    (and assets  (.contains assets target))
                    (and self    (.contains self target))
                    (and cpicker (.contains cpicker target))
                    (and palette (.contains palette target))
                    (= "foreignObject" (.-tagName ^js target)))
              (dom/stop-propagation event)
              (st/emit! dw/clear-edition-mode))))

        on-key-up
        (fn [event]
          (dom/stop-propagation event)
          (when (= (.-keyCode event) 27) ; ESC
            (do
              (st/emit! :interrupt)
              (st/emit! dw/clear-edition-mode))))

        on-mount
        (fn []
          (let [keys [(events/listen js/document EventType.MOUSEDOWN on-click-outside)
                      (events/listen js/document EventType.CLICK on-click-outside)
                      (events/listen js/document EventType.KEYUP on-key-up)]]
            (st/emit! (dwt/initialize-editor-state shape)
                      (dwt/select-all shape))
            #(do
               (st/emit! (dwt/finalize-editor-state shape))
               (doseq [key keys]
                 (events/unlistenByKey key)))))

        on-blur
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event))

        on-change
        (mf/use-callback
         (fn [val]
           (st/emit! (dwt/update-editor-state shape val))))

        on-editor
        (mf/use-callback
         (fn [editor]
           (st/emit! (dwt/update-editor editor))
           (when editor
             (.focus ^js editor))))

        handle-return
        (mf/use-callback
         (fn [event state]
           (st/emit! (dwt/update-editor-state shape (ted/editor-split-block state)))
           "handled"))
        ]

    (mf/use-layout-effect on-mount)

    [:div.text-editor {:ref self-ref
                       :class (dom/classnames
                               :align-top    (= (:vertical-align content "top") "top")
                               :align-center (= (:vertical-align content) "center")
                               :align-bottom (= (:vertical-align content) "bottom"))}
     [:> draft/Editor
      {:on-change on-change
       :on-blur on-blur
       :handle-return handle-return
       :custom-style-fn (fn [styles _]
                          (-> (ted/styles-to-attrs styles)
                              (sts/generate-text-styles)))
       :block-renderer-fn #(render-block % shape)
       :ref on-editor
       :editor-state state}]]))

(mf/defc text-shape-edit
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false
   ::mf/forward-ref true}
  [props ref]
  (let [{:keys [id x y width height grow-type] :as shape} (obj/get props "shape")]
    [:foreignObject {:transform (gsh/transform-matrix shape)
                     :x x :y y
                     :width  (if (#{:auto-width} grow-type) 100000 width)
                     :height (if (#{:auto-height :auto-width} grow-type) 100000 height)}

     [:& text-shape-edit-html {:shape shape :key (str id)}]]))
