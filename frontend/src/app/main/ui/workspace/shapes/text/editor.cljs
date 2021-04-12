;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

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

;; --- Text Editor Rendering

(mf/defc block-component
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")
        bprops   (obj/get props "blockProps")
        data     (obj/get bprops "data")
        style    (sts/generate-paragraph-styles (obj/get bprops "shape")
                                                (obj/get bprops "data"))
        dir      (:text-direction data "auto")]


    [:div {:style style :dir dir}
     [:> draft/EditorBlock props]]))

(mf/defc selection-component
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")]
    [:span {:style {:background "#ccc" :display "inline-block"}} children]))

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

(def default-decorator
  (ted/create-decorator "PENPOT_SELECTION" selection-component))

(def empty-editor-state
  (ted/create-editor-state nil default-decorator))

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

        blured        (mf/use-var false)

        on-key-up
        (fn [event]
          (dom/stop-propagation event)
          (when (= (.-keyCode event) 27) ; ESC
            (do
              (st/emit! :interrupt)
              (st/emit! dw/clear-edition-mode))))

        on-mount
        (fn []
          (let [keys [(events/listen js/document EventType.KEYUP on-key-up)]]
            (st/emit! (dwt/initialize-editor-state shape default-decorator)
                      (dwt/select-all shape))
            #(do
               (st/emit! (dwt/finalize-editor-state shape))
               (doseq [key keys]
                 (events/unlistenByKey key)))))

        on-blur
        (mf/use-callback
         (mf/deps shape state)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (reset! blured true)))

        on-focus
        (mf/use-callback
         (mf/deps shape state)
         (fn [event]
           (reset! blured false)))

        on-change
        (mf/use-callback
         (fn [val]
           (let [val (if (true? @blured)
                       (ted/add-editor-blur-selection val)
                       (ted/remove-editor-blur-selection val))]
             (st/emit! (dwt/update-editor-state shape val)))))

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

    [:div.text-editor
     {:ref self-ref
      :style {:cursor cur/text}
      :on-click (st/emitf (dwt/focus-editor))
      :class (dom/classnames
              :align-top    (= (:vertical-align content "top") "top")
              :align-center (= (:vertical-align content) "center")
              :align-bottom (= (:vertical-align content) "bottom"))}
     [:> draft/Editor
      {:on-change on-change
       :on-blur on-blur
       :on-focus on-focus
       :handle-return handle-return
       :strip-pasted-styles true
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
