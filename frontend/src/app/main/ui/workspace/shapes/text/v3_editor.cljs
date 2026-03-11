;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.text.v3-editor
  "Contenteditable DOM element for WASM text editor input"
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.text-editor :as text-editor]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def caret-blink-interval-ms 250)

(defn- sync-wasm-text-editor-content!
  "Sync WASM text editor content back to the shape via the standard
  commit pipeline. Called after every text-modifying input."
  [& {:keys [finalize?]}]
  (when-let [{:keys [shape-id content]}
             (text-editor/text-editor-sync-content)]
    (st/emit! (dwt/v2-update-text-shape-content
               shape-id content
               :update-name? true
               :finalize? finalize?))))

(defn- font-family-from-font-id [font-id]
  (if (str/includes? font-id "gfont-noto-sans")
    (let [lang (str/replace font-id #"gfont\-noto\-sans\-" "")]
      (if (>= (count lang) 3) (str/capital lang) (str/upper lang)))
    "Noto Color Emoji"))

(mf/defc text-editor
  "Contenteditable element positioned over the text shape to capture input events."
  {::mf/wrap-props false}
  [props]
  (let [shape     (obj/get props "shape")
        shape-id  (dm/get-prop shape :id)

        clip-id   (dm/str "text-edition-clip" shape-id)

        contenteditable-ref (mf/use-ref nil)
        composing?          (mf/use-state false)

        fallback-fonts    (wasm.api/fonts-from-text-content (:content shape) false)
        fallback-families (map (fn [font]
                                 (font-family-from-font-id (:font-id font))) fallback-fonts)

        [{:keys [x y width height]} transform]
        (let [{:keys [width height]} (wasm.api/get-text-dimensions shape-id)
              selrect-transform (mf/deref refs/workspace-selrect)
              [selrect transform] (dsh/get-selrect selrect-transform shape)
              selrect-height (:height selrect)
              selrect-width (:width selrect)
              max-width (max width selrect-width)
              max-height (max height selrect-height)
              valign (-> shape :content :vertical-align)
              y (:y selrect)
              y (case valign
                  "bottom" (+ y (- selrect-height height))
                  "center" (+ y (/ (- selrect-height height) 2))
                  y)]
          [(assoc selrect :y y :width max-width :height max-height) transform])

        on-composition-start
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

        on-cut
        (mf/use-fn
         (fn [^js event]
           (when (text-editor/text-editor-is-active?)
             (dom/prevent-default event)
             (when (text-editor/text-editor-get-selection)
               (let [text (text-editor/text-editor-export-selection)]
                 (.setData (.-clipboardData event) "text/plain" (or text ""))
                 (when (and text (seq text))
                   (text-editor/text-editor-delete-backward)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-cut"))))
             (when-let [node (mf/ref-val contenteditable-ref)]
               (set! (.-textContent node) "")))))

        on-key-down
        (mf/use-fn
         (fn [^js event]
           (when (and (text-editor/text-editor-is-active?)
                      (not @composing?))
             (let [key    (.-key event)
                   ctrl?  (or (.-ctrlKey event) (.-metaKey event))
                   shift? (.-shiftKey event)]

               (cond
                 ;; Escape: finalize and stop
                 (= key "Escape")
                 (do
                   (dom/prevent-default event)
                   (when-let [node (mf/ref-val contenteditable-ref)]
                     (.blur node)))

                 ;; Ctrl+A: select all (key is "a" or "A" depending on platform)
                 (and ctrl? (= (str/lower key) "a"))
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-select-all)
                   (wasm.api/request-render "text-select-all"))

                 ;; Enter
                 (= key "Enter")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-insert-paragraph)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-paragraph"))

                 ;; Backspace
                 (= key "Backspace")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-delete-backward)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-delete-backward"))

                 ;; Delete
                 (= key "Delete")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-delete-forward)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-delete-forward"))

                 ;; Arrow keys
                 (= key "ArrowLeft")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 0 shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "ArrowRight")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 1 shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "ArrowUp")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 2 shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "ArrowDown")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 3 shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "Home")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 4 shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "End")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 5 shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 ;; Let contenteditable handle text input via on-input
                 :else nil)))))

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
                 (set! (.-textContent node) ""))))))

        on-pointer-down
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt (dom/get-offset-position native-event)]
             (wasm.api/text-editor-pointer-down off-pt))))

        on-pointer-move
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt (dom/get-offset-position native-event)]
             (wasm.api/text-editor-pointer-move off-pt))))

        on-pointer-up
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt (dom/get-offset-position native-event)]
             (wasm.api/text-editor-pointer-up off-pt))))

        on-click
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt (dom/get-offset-position native-event)]
             (wasm.api/text-editor-set-cursor-from-offset off-pt))))

        on-double-click
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt (dom/get-offset-position native-event)]
             (wasm.api/text-editor-select-word-boundary off-pt))))

        on-focus
        (mf/use-fn
         (fn [^js _event]
           (wasm.api/text-editor-start shape-id)))

        on-blur
        (mf/use-fn
         (fn [^js _event]
           (sync-wasm-text-editor-content! {:finalize? true})
           (wasm.api/text-editor-stop)))

        style #js {:pointerEvents "all"
                   "--editor-container-width" (dm/str width "px")
                   "--editor-container-height" (dm/str height "px")
                   "--fallback-families" (if (seq fallback-families) (dm/str (str/join ", " fallback-families)) "sourcesanspro")}]

    ;; Focus contenteditable on mount
    (mf/use-effect
     (mf/deps contenteditable-ref)
     (fn []
       (when-let [node (mf/ref-val contenteditable-ref)]
         (.focus node))
       ;; Explicitly call on-blur here instead of relying on browser blur events,
       ;; because in Firefox blur is not reliably fired when leaving the text editor
       ;; by clicking elsewhere. The component does unmount when the shape is
       ;; deselected, so we can safely call the blur handler here to finalize the editor.
       on-blur))

    (mf/use-effect
     (fn []
       (let [timeout-id (atom nil)
             schedule-blink (fn schedule-blink []
                              (when (text-editor/text-editor-is-active?)
                                (wasm.api/request-render "cursor-blink"))
                              (reset! timeout-id (js/setTimeout schedule-blink caret-blink-interval-ms)))]
         (schedule-blink)
         (fn []
           (when @timeout-id
             (js/clearTimeout @timeout-id))))))

    ;; Composition and input events
    [:g.text-editor {:clip-path (dm/fmt "url(#%)" clip-id)
                     :transform (dm/str transform)
                     :data-testid "text-editor"}
     [:defs
      [:clipPath {:id clip-id}
       [:rect {:x x :y y :width width :height height}]]]

     [:foreignObject {:x x :y y :width width :height height}
      [:div {:on-click on-click
             :on-double-click on-double-click
             :on-pointer-down on-pointer-down
             :on-pointer-move on-pointer-move
             :on-pointer-up on-pointer-up
             :class (stl/css :text-editor)
             :style style}
       [:div
        {:ref contenteditable-ref
         :contentEditable true
         :suppressContentEditableWarning true
         :on-composition-start on-composition-start
         :on-composition-end on-composition-end
         :on-key-down on-key-down
         :on-input on-input
         :on-paste on-paste
         :on-copy on-copy
         :on-cut on-cut
         :on-focus on-focus
         :on-blur on-blur
         :id "text-editor-wasm-input"
         :class (dm/str (cur/get-dynamic "text" (:rotation shape))
                        " "
                        (stl/css :text-editor-container))
         :data-testid "text-editor-container"}]]]]))
