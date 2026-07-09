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
   [app.common.types.text :as txt]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.text-editor :as text-editor]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def caret-blink-interval-ms 250)

(defn- sync-wasm-text-editor-content!
  "Sync WASM text editor content back to the shape via the standard
  commit pipeline. Called after every text-modifying input."
  [& {:keys [finalize?]}]
  (when-let [{:keys [shape-id content]}
             (text-editor/text-editor-sync-content)]
    ;; Derive the layer name from the text so it tracks the content.
    (let [text (txt/content->text content)
          name (when (not= text "")
                 (txt/generate-shape-name text))]
      (st/emit! (dwt/v2-update-text-shape-content
                 shape-id content
                 :update-name? true
                 :name name
                 :finalize? finalize?)))))

(defn- reset-input-node
  "Empties the contenteditable capture surface and restores a collapsed caret
  inside it.

  The surface only exists to capture keystrokes for the WASM editor, so we clear
  it after every input. But removing the text node the caret lived in leaves the
  document without a valid selection, and the browser then stops firing `input`
  events for subsequent keystrokes (you can only type one character). Re-placing
  the caret inside the (now empty) node keeps input flowing, and we re-focus only
  if focus was actually lost so we don't reset the WASM cursor on every keystroke."
  [^js node]
  (when (some? node)
    (set! (.-textContent node) "")
    (when (not= (.-activeElement js/document) node)
      (.focus node))
    (when-let [sel (.getSelection js/window)]
      (let [range (.createRange js/document)]
        (.selectNodeContents range node)
        (.collapse range true)
        (.removeAllRanges sel)
        (.addRange sel range)))))

(defn- keep-input-alive
  "Keeps the capture surface able to receive further input WITHOUT clearing it.

  Unlike `reset-input-node`, this does not empty the surface. The macOS
  press-and-hold accent menu replaces the previously typed base character (its
  'marked text' when an accent is chosen, and that replacement only works
  while the base character is still present in the surface DOM. Clearing it
  drops the marked text, so the accent gets appended instead of replacing it,
  producing e.g. 'oö' instead of 'ö'."
  [^js node]
  (when (and (some? node)
             (not= (.-activeElement js/document) node))
    (.focus node)))

(defn- font-family-from-font-id [font-id]
  (if (str/includes? font-id "gfont-noto-sans")
    (let [lang (str/replace font-id #"gfont\-noto\-sans\-" "")]
      (if (>= (count lang) 3) (str/capital lang) (str/upper lang)))
    "Noto Color Emoji"))

(defn- composing-event?
  "True when a key/input event is part of an in-flight IME composition.

  Read from the browser event itself so it stays correct regardless of render
  timing or the relative ordering of compositionend.
  Note that , and that compositionstart
  dispatches after the first composing keydown event.

  We are checkign both isComposing and the keyCode (229, which is what is reported
  when using an IME), beause compositionstart dispatches after the first composing
  event. Note that also, on MacOS, the key that commits a composition (e.g. Enter
  in Japanese IME) dispatches its keydown while composition is still active, so we
  can't rely on a stale state and need to query the event itself."
  [^js event]
  (let [native (.-nativeEvent event)]
    (or (.-isComposing native)
        (= 229 (.-keyCode event)))))

(mf/defc text-editor*
  "Contenteditable element positioned over the text shape to capture input events."
  [{:keys [shape]}]
  (let [shape-id  (dm/get-prop shape :id)

        clip-id   (dm/str "text-edition-clip" shape-id)

        contenteditable-ref (mf/use-ref nil)

        ;; Number of characters the browser is about to replace via marked text
        ;; (macOS press-and-hold accent menu). Set on `beforeinput`, consumed on
        ;; `input`. See on-before-input / on-input.
        pending-replace-ref (mf/use-ref 0)

        fallback-fonts    (wasm.api/fonts-from-text-content (:content shape) false)
        fallback-families (map (fn [font]
                                 (font-family-from-font-id (:font-id font))) fallback-fonts)

        [{:keys [x y width height]} transform]
        (let [{:keys [width height]} (wasm.api/get-text-dimensions shape-id)
              selrect-transform (mf/deref refs/workspace-selrect)
              vbox (mf/deref refs/vbox)
              [selrect transform] (dsh/get-selrect selrect-transform shape)
              selrect-height (:height selrect)
              selrect-width (:width selrect)
              max-width (max width selrect-width)
              max-height (max height selrect-height)
              ;; During auto-width editing the shape width is trimmed to the content, so an
              ;; empty text box ends up only a few pixels wide. That is not enough room for
              ;; the caret and the contenteditable overlay may fail to receive input when it
              ;; is that small. Expand the overlay by one viewport width for auto-width texts
              ;; (mirroring the v2 editor) so typing works and the caret is not clipped.
              viewport-width (or (:width vbox) 0)
              overlay-width (if (= (:grow-type shape) :auto-width)
                              (+ max-width viewport-width)
                              max-width)
              valign (-> shape :content :vertical-align)
              y (:y selrect)
              y (case valign
                  "bottom" (+ y (- selrect-height height))
                  "center" (+ y (/ (- selrect-height height) 2))
                  y)]
          [(assoc selrect :y y :width overlay-width :height max-height) transform])

        on-composition-start
        (mf/use-fn
         (fn [_event]
           (text-editor/text-editor-composition-start)))

        on-composition-update
        (mf/use-fn
         (fn [event]
           ;; IME cancel (e.g. Escape on Linux ibus-mozc) fires compositionupdate
           ;; with an empty string; that must reach WASM to clear the preview text.
           (let [data (.-data event)]
             (when (some? data)
               (text-editor/text-editor-composition-update data)
               (sync-wasm-text-editor-content!)
               (wasm.api/request-render "text-composition"))
             (reset-input-node (mf/ref-val contenteditable-ref)))))

        on-composition-end
        (mf/use-fn
         (fn [^js event]
           (let [data (or (.-data event) "")]
             (text-editor/text-editor-composition-end data)
             (sync-wasm-text-editor-content!)
             (wasm.api/request-render "text-composition"))
           (reset-input-node (mf/ref-val contenteditable-ref))))

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
             (reset-input-node (mf/ref-val contenteditable-ref)))))

        on-copy
        (mf/use-fn
         (fn [^js event]
           (when (text-editor/text-editor-has-focus?)
             (dom/prevent-default event)
             (when (text-editor/text-editor-get-selection)
               (let [text (text-editor/text-editor-export-selection)]
                 (.setData (.-clipboardData event) "text/plain" text))))))

        on-cut
        (mf/use-fn
         (fn [^js event]
           (when (text-editor/text-editor-has-focus?)
             (dom/prevent-default event)
             (when (text-editor/text-editor-get-selection)
               (let [text (text-editor/text-editor-export-selection)]
                 (.setData (.-clipboardData event) "text/plain" (or text ""))
                 (when (and text (seq text))
                   (text-editor/text-editor-delete-backward)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-cut"))))
             (reset-input-node (mf/ref-val contenteditable-ref)))))

        on-key-down
        (mf/use-fn
         (fn [^js event]
           (when (and (text-editor/text-editor-has-focus?)
                      (not (composing-event? event)))
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
                   (text-editor/text-editor-delete-backward ctrl?)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-delete-backward"))

                 ;; Delete
                 (= key "Delete")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-delete-forward ctrl?)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render "text-delete-forward"))

                 ;; Insert
                 (= key "Insert")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-toggle-overtype-mode)
                   (wasm.api/request-render "text-overtype-mode"))

                 ;; Arrow keys
                 (= key "ArrowLeft")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 0 ctrl? shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "ArrowRight")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 1 ctrl? shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "ArrowUp")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 2 ctrl? shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "ArrowDown")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 3 ctrl? shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "Home")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 4 ctrl? shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 (= key "End")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 5 ctrl? shift?)
                   (wasm.api/request-render "text-cursor-move"))

                 ;; Let contenteditable handle text input via on-input
                 :else nil)))))

        ;; Native `beforeinput` listener (see the use-effect that registers it).
        ;; We use the native event, not React's synthetic `onBeforeInput`, because
        ;; only the native event reliably exposes `getTargetRanges()`.
        ;;
        ;; The macOS press-and-hold accent menu does NOT use composition events:
        ;; picking an accent arrives as a plain `insertText` whose target range
        ;; spans the previously typed base character (marked text), so the browser
        ;; replaces it instead of appending. We remember how many characters that
        ;; range covers so `on-input` can delete them from the WASM editor before
        ;; inserting the accented one. We ignore it while the WASM editor has an
        ;; active selection, since that selection is replaced by `insert-text`
        ;; itself and deleting extra characters would corrupt the content.
        on-before-input
        (mf/use-fn
         (fn [^js native]
           (mf/set-ref-val! pending-replace-ref 0)
           (when (and (= (.-inputType native) "insertText")
                      (not (.-isComposing native))
                      (not (text-editor/text-editor-has-selection?)))
             (let [ranges (.getTargetRanges native)]
               (when (pos? (.-length ranges))
                 (let [range (aget ranges 0)
                       n     (- (.-endOffset range) (.-startOffset range))]
                   (when (pos? n)
                     (mf/set-ref-val! pending-replace-ref n))))))))

        on-input
        (mf/use-fn
         (fn [^js event]
           (let [native-event (.-nativeEvent event)
                 input-type   (.-inputType native-event)
                 data         (.-data native-event)]
             ;; Skip composition-related input events - composition-end handles those
             (when (and (not (composing-event? event))
                        (not= input-type "insertCompositionText"))
               (when (and data (seq data))
                 ;; Marked-text replacement (macOS accent menu): remove the base
                 ;; character(s) the browser is replacing before inserting.
                 (let [pending (mf/ref-val pending-replace-ref)]
                   (dotimes [_ pending]
                     (text-editor/text-editor-delete-backward)))
                 (text-editor/text-editor-insert-text data)
                 (sync-wasm-text-editor-content!)
                 (wasm.api/request-render "text-input"))
               (mf/set-ref-val! pending-replace-ref 0)
               ;; IMPORTANT: do NOT clear the surface here (see keep-input-alive):
               ;; the browser must retain the just-typed character so the macOS
               ;; accent menu can replace it on the next input.
               (keep-input-alive (mf/ref-val contenteditable-ref))))))

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
           (wasm.api/text-editor-focus shape-id)))

        on-blur
        (mf/use-fn
         (fn [^js _event]
           (sync-wasm-text-editor-content! {:finalize? true})
           (wasm.api/text-editor-blur)))

        style #js {:pointerEvents "all"
                   "--editor-container-width" (dm/str width "px")
                   "--editor-container-height" (dm/str height "px")
                   "--fallback-families" (if (seq fallback-families) (dm/str (str/join ", " fallback-families)) "sourcesanspro")}]

    ;; Register the native `beforeinput` listener. React's synthetic
    ;; `onBeforeInput` does not expose `getTargetRanges()`, even with
    ;; nativeEvent (it's fully synthetic, composed of other two events).
    ;; We need `getTargetRranges` to detect macOS accent-menu replacements.
    ;; See https://github.com/react/react/issues/11211
    (mf/use-effect
     (mf/deps on-before-input)
     (fn []
       (when-let [node (mf/ref-val contenteditable-ref)]
         (.addEventListener node "beforeinput" on-before-input)
         (fn []
           (.removeEventListener node "beforeinput" on-before-input)))))

    ;; Focus contenteditable on mount
    (mf/use-effect
     (mf/deps contenteditable-ref)
     (fn []
       (when-let [node (mf/ref-val contenteditable-ref)]
         (.focus node))
       ;; On unmount, finalize the editor content and then dispose the WASM editor.
       ;; We finalize on unmount instead of relying on the browser blur event, because
       ;; it was not being reliable (timing issues, Firefox issues…)
       (fn []
         (on-blur)
         (text-editor/text-editor-dispose)
         (wasm.api/request-render "text-editor-dispose"))))

    (mf/use-effect
     (fn []
       (let [timeout-id (atom nil)
             schedule-blink (fn schedule-blink []
                              (when (text-editor/text-editor-has-focus?)
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
         ;; The surface retains typed text between keystrokes (see
         ;; keep-input-alive), so disable text assistance that would otherwise
         ;; rewrite that retained text and desync the WASM editor.
         ;; NOTE: this was already not working in v1/v2
         :spellCheck false
         :autoCorrect "off"
         :autoCapitalize "off"
         :on-composition-start on-composition-start
         :on-composition-update on-composition-update
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
