;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.text-editor-input
  "Contenteditable DOM element for WASM text editor input"
  (:require
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.text-editor :as text-editor]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn- sync-wasm-text-editor-content!
  "Sync WASM text editor content back to the shape via the standard
  commit pipeline. Called after every text-modifying input."
  [& {:keys [finalize?]}]
  (when-let [{:keys [shape-id content]} (text-editor/text-editor-sync-content)]
    (st/emit! (dwt/v2-update-text-shape-content
               shape-id content
               :update-name? true
               :finalize? finalize?))))

(mf/defc text-editor-input
  "Contenteditable element positioned over the text shape to capture input events."
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")
        zoom  (obj/get props "zoom")
        vbox  (obj/get props "vbox")

        contenteditable-ref (mf/use-ref nil)
        composing?          (mf/use-state false)

        ;; Calculate screen position from shape bounds
        shape-bounds (gsh/shape->rect shape)
        screen-x     (* (- (:x shape-bounds) (:x vbox)) zoom)
        screen-y     (* (- (:y shape-bounds) (:y vbox)) zoom)
        screen-w     (* (:width shape-bounds) zoom)
        screen-h     (* (:height shape-bounds) zoom)]

    ;; Focus contenteditable on mount
    (mf/use-effect
     (fn []
       (when-let [node (mf/ref-val contenteditable-ref)]
         (.focus node))
       js/undefined))

    ;; Animation loop for cursor blink
    (mf/use-effect
     (fn []
       (let [raf-id (atom nil)
             animate (fn animate []
                       (when (text-editor/text-editor-is-active?)
                         (wasm.api/request-render "cursor-blink")
                         (reset! raf-id (js/requestAnimationFrame animate))))]
         (animate)
         (fn []
           (when @raf-id
             (js/cancelAnimationFrame @raf-id))))))

    ;; Document-level keydown handler for control keys
    (mf/use-effect
     (fn []
       (let [on-doc-keydown
             (fn [e]
               (when (and (text-editor/text-editor-is-active?)
                          (not @composing?))
                 (let [key    (.-key e)
                       ctrl?  (or (.-ctrlKey e) (.-metaKey e))
                       shift? (.-shiftKey e)]
                   (cond
                     ;; Escape: finalize and stop
                     (= key "Escape")
                     (do
                       (dom/prevent-default e)
                       (sync-wasm-text-editor-content! :finalize? true)
                       (text-editor/text-editor-stop))

                     ;; Ctrl+A: select all (key is "a" or "A" depending on platform)
                     (and ctrl? (= (str/lower key) "a"))
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-select-all)
                       (wasm.api/request-render "text-select-all"))

                     ;; Enter
                     (= key "Enter")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-insert-paragraph)
                       (sync-wasm-text-editor-content!)
                       (wasm.api/request-render "text-paragraph"))

                     ;; Backspace
                     (= key "Backspace")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-delete-backward)
                       (sync-wasm-text-editor-content!)
                       (wasm.api/request-render "text-delete-backward"))

                     ;; Delete
                     (= key "Delete")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-delete-forward)
                       (sync-wasm-text-editor-content!)
                       (wasm.api/request-render "text-delete-forward"))

                     ;; Arrow keys
                     (= key "ArrowLeft")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-move-cursor 0 shift?)
                       (wasm.api/request-render "text-cursor-move"))

                     (= key "ArrowRight")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-move-cursor 1 shift?)
                       (wasm.api/request-render "text-cursor-move"))

                     (= key "ArrowUp")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-move-cursor 2 shift?)
                       (wasm.api/request-render "text-cursor-move"))

                     (= key "ArrowDown")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-move-cursor 3 shift?)
                       (wasm.api/request-render "text-cursor-move"))

                     (= key "Home")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-move-cursor 4 shift?)
                       (wasm.api/request-render "text-cursor-move"))

                     (= key "End")
                     (do
                       (dom/prevent-default e)
                       (text-editor/text-editor-move-cursor 5 shift?)
                       (wasm.api/request-render "text-cursor-move"))

                     ;; Let contenteditable handle text input via on-input
                     :else nil))))]
         (events/listen js/document EventType.KEYDOWN on-doc-keydown true)
         (fn []
           (events/unlisten js/document EventType.KEYDOWN on-doc-keydown true)))))

    ;; Composition and input events
    (let [on-composition-start
          (mf/use-fn
           (fn [_event]
             (reset! composing? true)))

          on-composition-end
          (mf/use-fn
           (fn [^js event]
             (reset! composing? false)
             (let [data (.-data event)]
               (when data
                 (text-editor/text-editor-insert-text data)
                 (sync-wasm-text-editor-content!)
                 (wasm.api/request-render "text-composition"))
               (when-let [node (mf/ref-val contenteditable-ref)]
                 (set! (.-textContent node) "")))))

          on-paste
          (mf/use-fn
           (fn [^js event]
             (dom/prevent-default event)
             (let [clipboard-data (.-clipboardData event)
                   text (.getData clipboard-data "text/plain")]
               (when (and text (seq text))
                 (text-editor/text-editor-insert-text text)
                 (sync-wasm-text-editor-content!)
                 (wasm.api/request-render "text-paste"))
               (when-let [node (mf/ref-val contenteditable-ref)]
                 (set! (.-textContent node) "")))))

          on-copy
          (mf/use-fn
           (fn [^js event]
             (when (text-editor/text-editor-is-active?)
               (dom/prevent-default event)
               (when (text-editor/text-editor-get-selection)
                 (let [text (text-editor/text-editor-export-selection)]
                   (.setData (.-clipboardData event) "text/plain" text))))))

          on-input
          (mf/use-fn
           (fn [^js event]
             (let [native-event (.-nativeEvent event)
                   input-type   (.-inputType native-event)
                   data         (.-data native-event)]
               ;; Skip composition-related input events - composition-end handles those
               (when (and (not @composing?)
                          (not= input-type "insertCompositionText"))
                 (when (and data (seq data))
                   (text-editor/text-editor-insert-text data)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-input"))
                 (when-let [node (mf/ref-val contenteditable-ref)]
                   (set! (.-textContent node) ""))))))]

      [:div
       {:ref contenteditable-ref
        :contentEditable true
        :suppressContentEditableWarning true
        :on-composition-start on-composition-start
        :on-composition-end on-composition-end
        :on-input on-input
        :on-paste on-paste
        :on-copy on-copy
        ;; FIXME on-click
        ;; :on-click on-click
        :id "text-editor-wasm-input"
        ;; FIXME
        :style {:position "absolute"
                :left (str screen-x "px")
                :top (str screen-y "px")
                :width (str screen-w "px")
                :height (str screen-h "px")
                :opacity 0
                :overflow "hidden"
                :white-space "pre"
                :cursor "text"
                :z-index 10}}])))
