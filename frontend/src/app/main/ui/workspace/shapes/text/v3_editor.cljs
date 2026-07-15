;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.shapes.text.v3-editor
  "Contenteditable DOM element for WASM text editor input"
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.text :as txt]
   [app.common.types.text.japanese-layout :as jl]
   [app.config :as cf]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.text-editor :as text-editor]
   [app.render-wasm.text-paste :as text-paste]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.text.clipboard :as text-clipboard]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def caret-blink-interval-ms 250)

;; The open workspace context menu, if any (see the Escape handler below).
(def ^:private menu-selector "[data-testid='context-menu']")

;; Elements carrying this attr keep the edit alive when focus moves onto them (see `keep-editing-on-blur?`).
(def ^:private keep-editing-selector "[data-keep-editing-on-blur]")

(defn- keep-editing-on-blur?
  "True when a surface `blur` must NOT exit the editor:
   - Firefox triggering a blur when MacOS Character Viewer is open
   - Focus switched to a data-keep-editing-on-blur region (e.g. typography options),
     ancestors or descendants"
  [^js event ^js surface]
  (or (= (.-activeElement js/document) surface)
      (when-let [related (dom/get-related-target event)]
        (or (some? (.closest related keep-editing-selector))
            (some? (.querySelector related keep-editing-selector))))))

(defn- sync-wasm-text-editor-content!
  "Sync WASM text editor content back to the shape via the standard
  commit pipeline. Called after every text-modifying input."
  [& {:keys [finalize?]}]
  (when-let [event (dwt/v3-sync-editor-content :finalize? finalize?)]
    (st/emit! event)))

;; Keys that move/reset the caret (or delete): pressing any abandons the pending
;; caret style. Plain character keys instead reach `on-input`, which consumes it.
(def ^:private caret-abandon-keys
  #{"ArrowLeft" "ArrowRight" "ArrowUp" "ArrowDown"
    "Home" "End" "PageUp" "PageDown"
    "Enter" "Backspace" "Delete" "Escape" "Tab"})

(defn- caret-position
  "Collapsed caret as {:para :offset} from the WASM selection, or nil."
  []
  (when-let [{:keys [focus-para focus-offset]} (text-editor/text-editor-get-selection)]
    {:para focus-para :offset focus-offset}))

(defn- typed-range
  "Normalized range covering the text inserted between `before` and `after`, or nil."
  [before after]
  (when (and before after)
    (if (or (< (:para before) (:para after))
            (and (= (:para before) (:para after))
                 (<= (:offset before) (:offset after))))
      {:start-para (:para before) :start-offset (:offset before)
       :end-para   (:para after)  :end-offset   (:offset after)}
      {:start-para (:para after)  :start-offset (:offset after)
       :end-para   (:para before) :end-offset   (:offset before)})))

(defn- commit-restyled-content
  "Save `content` restyled after an insertion, renaming the shape after its text."
  [shape-id content]
  (let [text (txt/content->text content)
        name (when (not= text "") (txt/generate-shape-name text))]
    (st/emit! (dwt/v2-update-text-shape-content
               shape-id content
               :update-name? true
               :name name))))

(defn- sync-with-pending-caret-styles!
  "Commit an insertion that consumed a pending caret style: sync the new text,
   then restyle the just-typed `range` into its own span. `before` is the
   pre-insert caret."
  [shape-id before]
  (let [range (typed-range before (caret-position))]
    ;; Sync first so the cached content stays index-aligned with WASM.
    (text-editor/text-editor-sync-content)
    (if-let [{:keys [content]} (wasm.api/apply-pending-caret-styles! shape-id range)]
      (commit-restyled-content shape-id content)
      (sync-wasm-text-editor-content!))))

;; Bigger HTML is pasted as plain text rather than walked.
(def ^:private max-paste-html-length 1000000)

(defn- clipboard->fragment
  "The paste fragment for `data`: its HTML when allowed and it has text, else its plain text."
  [^js data html-paste?]
  (let [html (when html-paste? (.getData data "text/html"))]
    (or (when (< 0 (count html) max-paste-html-length)
          (text-clipboard/html->fragment html))
        (text-clipboard/text->fragment (.getData data "text/plain")))))

(defn- selection-start
  "Start of the WASM selection as {:para :offset}: where pasted text goes."
  []
  (when-let [{:keys [anchor-para anchor-offset focus-para focus-offset]}
             (text-editor/text-editor-get-selection)]
    (if (or (< anchor-para focus-para)
            (and (= anchor-para focus-para) (<= anchor-offset focus-offset)))
      {:para anchor-para :offset anchor-offset}
      {:para focus-para :offset focus-offset})))

(defn- paste-fragment
  "Insert the text of `fragment`, then restyle it with the fragment overrides."
  [fragment]
  (let [shape-id (text-editor/text-editor-get-active-shape-id)
        start    (selection-start)]
    (text-editor/text-editor-insert-text (text-paste/fragment->text fragment))
    (if (and (some? start) (text-paste/styled? fragment))
      (do
        ;; Sync first so the cached content stays index-aligned with WASM.
        (text-editor/text-editor-sync-content)
        (if-let [{:keys [content]} (wasm.api/apply-paste-styles shape-id fragment start)]
          (commit-restyled-content shape-id content)
          (sync-wasm-text-editor-content!)))
      (sync-wasm-text-editor-content!))))

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

(defn- secondary-button?
  "True for a secondary click, which opens the context menu: the right button,
   or the macOS Ctrl+Click that stands in for it and reports button 0."
  [^js event]
  (or (= 2 (.-button event))
      (and (cf/check-platform? :macos) (kbd/ctrl? event))))

(defn- primary-button-pressed?
  "True while the left button is held. `buttons` is a bitmask: `pos?` would also
   match the right button."
  [^js event]
  (pos? (bit-and (.-buttons event) 1)))

(defn- double-click?
  [^js native-event]
  (= (.-detail native-event) 2))

(defn- triple-click?
  [^js native-event]
  (>= (.-detail native-event) 3))

(defn- input-surface-class
  "Class list for the contenteditable capture surface.

  Mousetrap's `stopCallback` drops every keystroke whose target is
  contentEditable, so without the `mousetrap` class (as in V1/V2) the text
  shortcuts (Ctrl+B, Ctrl+I, …) never reach the dispatcher. The hover cursor
  lives on the overlay: the surface is pointer-events:none."
  []
  (dm/str "mousetrap " (stl/css :text-editor-container)))

(def ^:private ime-surface-collapse
  "Scale applied along the inline axis of the capture surface, so its text
  (retained input or an in-flight composition) never moves the DOM caret."
  0.001)

(defn- update-ime-caret!
  "Move the hidden contenteditable capture surface onto the WASM caret so the
  browser's IME candidate window opens next to the edited text. Returns the
  caret rectangle it anchored to, or nil when there is none yet.

  Must only be called while the editor is idle, never during or immediately
  before an IME composition: real IMEs (ibus-mozc) abort and commit the
  pending text on any mutation of the composing element, including style
  writes on the keydown-229 that precedes compositionstart. The surface is
  therefore kept on the caret at all times, updated after every
  caret-affecting operation (click, arrows, typing, commit). During a
  composition the window follows the WASM caret by moving the surface's
  wrapper instead (see `follow-ime-caret!`).

  The browser anchors the candidate window to the DOM caret inside the
  surface. The surface is collapsed along the inline axis, so neither the text
  it retains between keystrokes (see `keep-input-alive`) nor the composition
  moves that caret. The block axis keeps the caret cell size (font-size = line
  height horizontally, column width vertically).

  The IME opens the window below the caret. Horizontally that clears the
  current line. Vertically the text itself flows down, so the surface (with
  `writing-mode: vertical-rl` and `line-height: 1`, making its column exactly
  one caret column wide) sits just right of the caret column: the window opens
  beside the composition instead of over it.

  The caret rectangle is in the same page space as the overlay foreignObject,
  whose top-left and width are passed as `origin`."
  [^js node ^js wrapper origin]
  (when (and (some? node) (some? origin))
    (when-let [{:keys [x y width height] :as rect} (text-editor/text-editor-get-cursor-rect)]
      (let [style (.-style node)]
        (if (:vertical? origin)
          (do
            (set! (.-writingMode style) "vertical-rl")
            (set! (.-fontSize style) (dm/str width "px"))
            (set! (.-lineHeight style) "1")
            (set! (.-transformOrigin style) "100% 0")
            (set! (.-transform style) (dm/str "scaleY(" ime-surface-collapse ")"))
            ;; Surface column starts past the caret column's right edge, plus a
            ;; small gap so the window border stays clear of the glyphs.
            (set! (.-left style) (dm/str (- (+ x (* 2.15 width)) (:x origin) (:width origin)) "px")))
          (do
            (set! (.-writingMode style) "")
            (set! (.-fontSize style) (dm/str height "px"))
            (set! (.-lineHeight style) "")
            (set! (.-transformOrigin style) "0 0")
            (set! (.-transform style) (dm/str "scaleX(" ime-surface-collapse ")"))
            (set! (.-left style) (dm/str (- x (:x origin)) "px"))))
        (set! (.-top style) (dm/str (- y (:y origin)) "px"))
        (when (some? wrapper)
          (set! (.. wrapper -style -transform) ""))
        rect))))

(defn- text-before-selection
  "UTF-16 length of the surface text before the DOM selection focus, or nil
  when the selection is not inside `node`. Reading it mutates nothing."
  [^js node]
  (let [sel (.getSelection js/window)]
    (when (and (some? node) (some? sel) (pos? (.-rangeCount sel))
               (.contains node (.-focusNode sel)))
      (let [range (.createRange js/document)]
        (.setStart range node 0)
        (.setEnd range (.-focusNode sel) (.-focusOffset sel))
        (.-length (.toString range))))))

(defn changed-span-end
  "Code point index, in `new-text`, where the part that differs from
  `old-text` ends: a newly typed kana or a newly picked candidate."
  [old-text new-text]
  (let [old    (js/Array.from old-text)
        new    (js/Array.from new-text)
        n-old  (.-length old)
        n-new  (.-length new)
        prefix (loop [i 0]
                 (if (and (< i n-old) (< i n-new) (= (aget old i) (aget new i)))
                   (recur (inc i))
                   i))
        suffix (loop [i 0]
                 (if (and (< i (- n-old prefix))
                          (< i (- n-new prefix))
                          (= (aget old (- n-old 1 i)) (aget new (- n-new 1 i))))
                   (recur (inc i))
                   i))]
    (- n-new suffix)))

(defn- follow-ime-caret!
  "Translate the surface wrapper by how far the WASM caret moved from `anchor`,
  the caret rectangle the surface was placed on. Only the wrapper is written,
  never the composing element, so it is safe while a composition is in flight.
  Returns nil when the caret rectangle is not available yet."
  [^js wrapper anchor]
  (when (and (some? wrapper) (some? anchor))
    (when-let [{:keys [x y]} (text-editor/text-editor-get-cursor-rect)]
      (set! (.. wrapper -style -transform)
            (dm/str "translate(" (- x (:x anchor)) "px, " (- y (:y anchor)) "px)")))))

(mf/defc text-editor*
  "Contenteditable element positioned over the text shape to capture input events."
  [{:keys [shape]}]
  (let [shape-id  (dm/get-prop shape :id)

        clip-id   (dm/str "text-edition-clip" shape-id)

        vertical? (jl/vertical-text-content? (:content shape))

        contenteditable-ref (mf/use-ref nil)

        ;; Number of characters the browser is about to replace via marked text
        ;; (macOS press-and-hold accent menu). Set on `beforeinput`, consumed on
        ;; `input`. See on-before-input / on-input.
        pending-replace-ref (mf/use-ref 0)

        ;; Tracks an in-flight pointer drag-selection so `on-pointer-move` only
        ;; repaints the selection overlay while a drag is active (mirrors the
        ;; WASM `is_pointer_selection_active` guard), not on every hover move.
        dragging-ref (mf/use-ref false)

        deferred-press-ref (mf/use-ref nil)

        ;; Overlay foreignObject top-left and width in page space, used to
        ;; translate the WASM caret rectangle into an offset inside it.
        origin-ref (mf/use-ref nil)

        ;; True between compositionstart and compositionend; guards the surface
        ;; against any repositioning while the IME owns it.
        composing-ref (mf/use-ref false)

        ;; Positioned wrapper of the surface, moved during a composition so the
        ;; IME window follows the WASM caret (see `follow-ime-caret!`).
        wrapper-ref (mf/use-ref nil)

        ;; Caret rectangle the surface was last placed on while idle.
        anchor-ref (mf/use-ref nil)

        ;; Surface text length (UTF-16) before the composition, and the current
        ;; composition text: locate the IME cursor within the preview.
        composition-base-ref (mf/use-ref 0)
        composition-text-ref (mf/use-ref "")

        ;; IME cursor offset last read from the DOM selection, nil right after a
        ;; text change until the next read.
        synced-offset-ref (mf/use-ref nil)

        fallback-fonts    (wasm.api/fonts-from-text-content (:content shape) false)
        fallback-families (map (fn [font]
                                 (font-family-from-font-id (:font-id font))) fallback-fonts)

        [{:keys [x y width height ime-width]} transform]
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
          ;; Vertical text anchors the IME window right of the caret column,
          ;; which for the rightmost column lies past the overlay. The
          ;; foreignObject is widened (not the clip) so the anchor stays inside
          ;; it; otherwise the browser scrolls it to reveal the caret and
          ;; cancels the offset.
          [(assoc selrect
                  :y y :width overlay-width :height max-height
                  :ime-width (cond-> overlay-width vertical? (+ viewport-width)))
           transform])

        schedule-ime-caret!
        (mf/use-fn
         (fn []
           ;; Wait two frames so the pending WASM render rebuilds the text
           ;; layout the caret rect reads, then retry for a while: right after
           ;; mount the rect can stay unavailable until the first full layout.
           ;; Skipped when a composition began in the meantime — the surface
           ;; must not move while the IME owns it.
           (letfn [(attempt [tries]
                     (when-not (mf/ref-val composing-ref)
                       (if-let [rect (update-ime-caret!
                                      (mf/ref-val contenteditable-ref)
                                      (mf/ref-val wrapper-ref)
                                      (mf/ref-val origin-ref))]
                         (mf/set-ref-val! anchor-ref rect)
                         (when (pos? tries)
                           (js/requestAnimationFrame #(attempt (dec tries)))))))]
             (js/requestAnimationFrame
              (fn []
                (js/requestAnimationFrame #(attempt 30)))))))

        schedule-ime-follow!
        (mf/use-fn
         (fn []
           ;; Same frame wait and retries as `schedule-ime-caret!`: the preview
           ;; update clears the text layout the caret rect reads until the
           ;; pending render rebuilds it. Only applies mid-composition.
           (letfn [(attempt [tries]
                     (when (and (mf/ref-val composing-ref)
                                (nil? (follow-ime-caret!
                                       (mf/ref-val wrapper-ref)
                                       (mf/ref-val anchor-ref)))
                                (pos? tries))
                       (js/requestAnimationFrame #(attempt (dec tries)))))]
             (js/requestAnimationFrame
              (fn []
                (js/requestAnimationFrame #(attempt 30)))))))

        ;; Moves the WASM caret inside the preview and the IME window with it.
        ;; Synchronous: the browser reports the caret bounds to the IME right
        ;; after the composition/selection update that triggered this, so a
        ;; deferred move would only reach the IME on its next update.
        place-composition-caret!
        (mf/use-fn
         (fn [offset]
           (text-editor/text-editor-set-composition-cursor offset)
           (when (nil? (follow-ime-caret! (mf/ref-val wrapper-ref) (mf/ref-val anchor-ref)))
             (schedule-ime-follow!))))

        ;; When only the IME cursor moves (switching clauses), the browser keeps
        ;; the DOM selection on it: mirror it on the WASM caret. The first read
        ;; after a text change is just the baseline; text changes place the
        ;; caret themselves (see on-composition-update).
        sync-composition-cursor!
        (mf/use-fn
         (fn []
           (when (mf/ref-val composing-ref)
             (when-let [focus (text-before-selection (mf/ref-val contenteditable-ref))]
               (let [text     (mf/ref-val composition-text-ref)
                     units    (-> (- focus (mf/ref-val composition-base-ref))
                                  (max 0)
                                  (min (count text)))
                     cursor   (.-length (js/Array.from (subs text 0 units)))
                     previous (mf/ref-val synced-offset-ref)]
                 (mf/set-ref-val! synced-offset-ref cursor)
                 (when (and (some? previous) (not= cursor previous))
                   (place-composition-caret! cursor)
                   (wasm.api/render-text-editor-overlay!)))))))

        ;; Programmatic .focus() on the surface does not reliably fire
        ;; focus/focusin in Chromium (contenteditable inside an SVG
        ;; foreignObject): the node becomes document.activeElement but React's
        ;; on-focus never dispatches, leaving the WASM editor unfocused and
        ;; every caret-rect read null. So wherever we focus programmatically,
        ;; the WASM focus is established explicitly instead of relying on the
        ;; focus event.
        focus-editor!
        (mf/use-fn
         (mf/deps shape-id)
         (fn []
           (when-let [node (mf/ref-val contenteditable-ref)]
             (when (not= (.-activeElement js/document) node)
               (.focus node)))
           ;; Re-focusing resets transient WASM editor state (overtype mode,
           ;; pending events), so only do it when this shape lacks the focus.
           (when-not (and (text-editor/text-editor-has-focus?)
                          (= shape-id (text-editor/text-editor-get-active-shape-id)))
             (wasm.api/text-editor-focus shape-id))
           (schedule-ime-caret!)))

        on-composition-start
        (mf/use-fn
         (fn [_event]
           (mf/set-ref-val! composing-ref true)
           (mf/set-ref-val! composition-text-ref "")
           (mf/set-ref-val! synced-offset-ref nil)
           (mf/set-ref-val! composition-base-ref
                            (let [node (mf/ref-val contenteditable-ref)]
                              (or (text-before-selection node)
                                  (count (.-textContent node)))))
           ;; IME composition supplies its own text; drop any pending caret style.
           (text-editor/clear-pending-caret-styles!)
           (text-editor/text-editor-composition-start)))

        on-composition-update
        (mf/use-fn
         (mf/deps shape-id)
         (fn [event]
           ;; IME cancel (e.g. Escape on Linux ibus-mozc) fires compositionupdate
           ;; with an empty string; that must reach WASM to clear the preview text.
           ;;
           ;; While the composition is in flight the browser owns the capture
           ;; surface, so this handler must not touch it: no clearing, no style
           ;; writes, and no store dispatch. A store sync re-renders this
           ;; component (shape content/name/dimensions, foreignObject
           ;; attributes, CSS vars), and any such churn makes a real IME commit
           ;; the pending kana and restart (typing "ni" commits ん then い
           ;; instead of composing に). The WASM editor keeps the preview in its
           ;; own state and paints it via the render request; the store is
           ;; synced once on compositionend. Only the surface wrapper moves, so
           ;; the IME window follows the WASM caret (wrapping, candidates).
           ;;
           ;; The caret goes to the end of the part that changed: a typed kana,
           ;; a converted word, a picked candidate (even in a middle clause).
           (let [data (.-data event)]
             (when (some? data)
               (let [previous (mf/ref-val composition-text-ref)]
                 (mf/set-ref-val! composition-text-ref data)
                 (mf/set-ref-val! synced-offset-ref nil)
                 (text-editor/text-editor-composition-update data)
                 ;; The preview never reaches the store, so request its
                 ;; fallback faces here or it shows missing glyphs until commit.
                 (wasm.api/load-composition-fonts! shape-id data)
                 (place-composition-caret! (changed-span-end previous data))
                 (wasm.api/request-render-preserving-target "text-composition"))))))

        on-composition-end
        (mf/use-fn
         (fn [^js event]
           (mf/set-ref-val! composing-ref false)
           (let [data (or (.-data event) "")]
             (text-editor/text-editor-composition-end data)
             (sync-wasm-text-editor-content!)
             (wasm.api/request-render-preserving-target "text-composition"))
           (reset-input-node (mf/ref-val contenteditable-ref))
           ;; Move the surface onto the caret, now past the committed text.
           (schedule-ime-caret!)))

        on-paste
        (mf/use-fn
         (fn [^js event]
           (dom/prevent-default event)
           ;; Pasted text keeps the surrounding style; drop any pending caret style.
           (text-editor/clear-pending-caret-styles!)
           (when-let [fragment (some-> (.-clipboardData event)
                                       (clipboard->fragment
                                        (features/active-feature? @st/state "text-editor-wasm/v1-html-paste")))]
             (paste-fragment fragment)
             (wasm.api/request-render-preserving-target "text-paste"))
           (reset-input-node (mf/ref-val contenteditable-ref))
           (schedule-ime-caret!)))

        on-copy
        (mf/use-fn
         (fn [^js event]
           (when (text-editor/text-editor-has-focus?)
             (dom/prevent-default event)
             (when (text-editor/text-editor-has-selection?)
               (let [text (or (text-editor/text-editor-export-selection) "")
                     html (clipboard/plain-text->html text)
                     data (.-clipboardData event)]
                 ;; text/html matters on Windows: many apps prefer CF_HTML, and
                 ;; without it they can pick up the empty contenteditable `<br>`.
                 (.setData data "text/plain" text)
                 (.setData data "text/html" html))))))

        on-cut
        (mf/use-fn
         (fn [^js event]
           (when (text-editor/text-editor-has-focus?)
             (dom/prevent-default event)
             (when (text-editor/text-editor-has-selection?)
               (let [text (or (text-editor/text-editor-export-selection) "")
                     html (clipboard/plain-text->html text)
                     data (.-clipboardData event)]
                 (.setData data "text/plain" text)
                 (.setData data "text/html" html)
                 (when (seq text)
                   (text-editor/text-editor-delete-backward)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render-preserving-target "text-cut"))))
             (reset-input-node (mf/ref-val contenteditable-ref))
             (schedule-ime-caret!))))

        on-key-down
        (mf/use-fn
         (fn [^js event]
           ;; IME keydowns (keyCode 229) must not touch the surface at all: by
           ;; the time the 229 reaches the DOM the IME already owns the surface,
           ;; and even a pre-compositionstart style write makes ibus abort and
           ;; commit. The surface is already sitting on the caret from the last
           ;; idle repositioning (see `update-ime-caret!`).
           (when (and (text-editor/text-editor-has-focus?)
                      (not (composing-event? event)))
             (let [key    (.-key event)
                   ctrl?  (or (.-ctrlKey event) (.-metaKey event))
                   shift? (.-shiftKey event)]
               ;; Ctrl+A adds select-all to the caret-abandon-keys set.
               (when (or (contains? caret-abandon-keys key)
                         (and ctrl? (= (str/lower key) "a")))
                 (text-editor/clear-pending-caret-styles!))
               (cond
                 ;; NOTE: Escape is handled in a document key-up listener (see effect below).

                 ;; Ctrl+A: select all (key is "a" or "A" depending on platform)
                 (and ctrl? (= (str/lower key) "a"))
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-select-all)
                   (wasm.api/render-text-editor-overlay!))

                 ;; Enter
                 (= key "Enter")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-insert-paragraph)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render-preserving-target "text-paragraph"))

                 ;; Backspace
                 (= key "Backspace")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-delete-backward ctrl?)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render-preserving-target "text-delete-backward"))

                 ;; Delete
                 (= key "Delete")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-delete-forward ctrl?)
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render-preserving-target "text-delete-forward"))

                 ;; Shift+Tab falls through to the browser, so the keyboard can
                 ;; still leave the editor.
                 (and (= key "Tab") (not shift?))
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-insert-text "\t")
                   (sync-wasm-text-editor-content!)
                   (wasm.api/request-render-preserving-target "text-tab"))

                 ;; Insert
                 (= key "Insert")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-toggle-overtype-mode)
                   (wasm.api/render-text-editor-overlay!))

                 ;; Arrow keys
                 (= key "ArrowLeft")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 0 ctrl? shift?)
                   (wasm.api/render-text-editor-overlay!))

                 (= key "ArrowRight")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 1 ctrl? shift?)
                   (wasm.api/render-text-editor-overlay!))

                 (= key "ArrowUp")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 2 ctrl? shift?)
                   (wasm.api/render-text-editor-overlay!))

                 (= key "ArrowDown")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 3 ctrl? shift?)
                   (wasm.api/render-text-editor-overlay!))

                 (= key "Home")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 4 ctrl? shift?)
                   (wasm.api/render-text-editor-overlay!))

                 (= key "End")
                 (do
                   (dom/prevent-default event)
                   (text-editor/text-editor-move-cursor 5 ctrl? shift?)
                   (wasm.api/render-text-editor-overlay!))

                 ;; Let contenteditable handle text input via on-input
                 :else nil)
               ;; Any handled key may have moved the caret; keep the surface on
               ;; it so the next IME sequence anchors correctly. Plain character
               ;; keys reschedule again from on-input after the insert.
               (schedule-ime-caret!)))))

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
             ;; The DOM now holds the updated composition and its IME cursor.
             (when (= input-type "insertCompositionText")
               (sync-composition-cursor!))
             ;; Skip composition-related input events - composition-end handles those
             (when (and (not (composing-event? event))
                        (not= input-type "insertCompositionText"))
               (when (and data (seq data))
                 ;; Marked-text replacement (macOS accent menu): remove the base
                 ;; character(s) the browser is replacing before inserting.
                 (let [pending (mf/ref-val pending-replace-ref)]
                   (dotimes [_ pending]
                     (text-editor/text-editor-delete-backward)))
                 (let [shape-id        (text-editor/text-editor-get-active-shape-id)
                       ;; The inserted character adopts a pending caret style, if any.
                       pending-styles? (some? (text-editor/get-pending-caret-styles shape-id))
                       before          (when pending-styles? (caret-position))]
                   (text-editor/text-editor-insert-text data)
                   (if pending-styles?
                     (sync-with-pending-caret-styles! shape-id before)
                     (sync-wasm-text-editor-content!)))
                 (wasm.api/request-render-preserving-target "text-input"))
               (mf/set-ref-val! pending-replace-ref 0)
               ;; IMPORTANT: do NOT clear the surface here (see keep-input-alive):
               ;; the browser must retain the just-typed character so the macOS
               ;; accent menu can replace it on the next input.
               (keep-input-alive (mf/ref-val contenteditable-ref))
               (schedule-ime-caret!)))))

        on-pointer-down
        (mf/use-fn
         (fn [^js event]
           ;; The capture surface is pointer-events:none (it sits on the caret,
           ;; not under the pointer), so it must be focused programmatically.
           ;; preventDefault stops the browser from moving focus to the body on
           ;; mousedown, which would blur the surface right back.
           (dom/prevent-default event)
           (focus-editor!)
           (when-not (secondary-button? event)
             (let [native-event (dom/event->native-event event)
                   off-pt       (dom/get-offset-position native-event)]
               ;; Repositioning the caret abandons the pending caret style (also
               ;; covers click and double-click, which fire pointer-down first).
               (text-editor/clear-pending-caret-styles!)
               (if (.-shiftKey event)
                 (do
                   (mf/set-ref-val! dragging-ref true)
                   (wasm.api/text-editor-pointer-down-extend off-pt)
                   ;; Repaint the caret over the cached tiles instead of a full
                   ;; render, which flashes at high zoom.
                   (wasm.api/render-text-editor-overlay!))
                 (mf/set-ref-val! deferred-press-ref off-pt))))))

        on-pointer-move
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt       (dom/get-offset-position native-event)]
             (when-let [pressed-pt (and (primary-button-pressed? native-event)
                                        (mf/ref-val deferred-press-ref))]
               (mf/set-ref-val! deferred-press-ref nil)
               (mf/set-ref-val! dragging-ref true)
               (wasm.api/text-editor-pointer-down pressed-pt))
             (wasm.api/text-editor-pointer-move off-pt)
             ;; Only while dragging: `text-editor-pointer-move` is a no-op
             ;; otherwise, so avoid repainting on plain hover.
             (when (mf/ref-val dragging-ref)
               (wasm.api/render-text-editor-overlay!)))))

        on-pointer-up
        (mf/use-fn
         (fn [^js event]
           (when-not (secondary-button? event)
             (let [native-event (dom/event->native-event event)
                   off-pt       (dom/get-offset-position native-event)
                   dragging?    (mf/ref-val dragging-ref)]
               (mf/set-ref-val! dragging-ref false)
               (mf/set-ref-val! deferred-press-ref nil)
               (wasm.api/text-editor-pointer-up off-pt)
               ;; Without a drag there is no pointer selection to close; the
               ;; caret is placed by `on-click`.
               (when dragging?
                 (wasm.api/render-text-editor-overlay!))
               (schedule-ime-caret!)))))

        on-click
        (mf/use-fn
         (fn [^js event]
           (when-not (secondary-button? event)
             (let [native-event (dom/event->native-event event)
                   off-pt       (dom/get-offset-position native-event)]
               (cond
                 (triple-click? native-event)
                 (do
                   (wasm.api/text-editor-select-paragraph off-pt)
                   (wasm.api/render-text-editor-overlay!))

                 ;; `dblclick` selects the word right after. Shift+click still goes
                 ;; through: WASM consumes its skip-click flag there.
                 (and (double-click? native-event)
                      (not (.-shiftKey event)))
                 nil

                 :else
                 (do
                   (wasm.api/text-editor-set-cursor-from-offset off-pt)
                   (wasm.api/render-text-editor-overlay!)))
               (schedule-ime-caret!)))))

        on-double-click
        (mf/use-fn
         (fn [^js event]
           (let [native-event (dom/event->native-event event)
                 off-pt (dom/get-offset-position native-event)]
             (wasm.api/text-editor-select-word-boundary off-pt)
             (wasm.api/render-text-editor-overlay!)
             (schedule-ime-caret!))))

        on-context-menu
        (mf/use-fn
         (mf/deps shape-id)
         (fn [^js event]
           (dom/prevent-default event)
           ;; Without this the viewport handler opens the shape menu instead.
           (dom/stop-propagation event)
           (let [position       (dom/get-client-position event)
                 has-selection? (boolean (text-editor/text-editor-has-selection?))]
             ;; With nothing selected the caret goes where the user pointed, so a
             ;; paste from the menu lands there.
             (when-not has-selection?
               (let [off-pt (dom/get-offset-position (dom/event->native-event event))]
                 ;; Moving the caret abandons the pending caret style, as it does
                 ;; on every other path that moves it.
                 (text-editor/clear-pending-caret-styles!)
                 (wasm.api/text-editor-set-cursor-from-offset off-pt)
                 (wasm.api/render-text-editor-overlay!)
                 (schedule-ime-caret!)))
             ;; Deferred: the dropdown closes itself on a document `contextmenu`,
             ;; which would close the menu this very event is opening.
             (ts/schedule
              #(st/emit! (dw/show-text-context-menu
                          {:position position
                           :shape-id shape-id
                           :has-selection? has-selection?}))))))

        on-focus
        (mf/use-fn
         (fn [^js _event]
           (wasm.api/text-editor-focus shape-id)
           (schedule-ime-caret!)))

        on-blur
        (mf/use-fn
         (fn [^js event]
           ;; A blur exits the editor unless keep-editing-on-blur? is true
           (when-not (and (some? event)
                          (keep-editing-on-blur? event (mf/ref-val contenteditable-ref)))
             (text-editor/clear-pending-caret-styles!)
             (sync-wasm-text-editor-content! {:finalize? true})
             (wasm.api/text-editor-blur))))

        style #js {:pointerEvents "all"
                   "--editor-container-width" (dm/str width "px")
                   "--editor-container-height" (dm/str height "px")
                   "--fallback-families" (if (seq fallback-families) (dm/str (str/join ", " fallback-families)) "sourcesanspro")}]

    ;; Keep the caret-translation origin in sync with the current render.
    (mf/set-ref-val! origin-ref {:x x :y y :width ime-width :vertical? vertical?})

    ;; Exit on Escape via a document key-up listener (like v2). On key-down the trailing
    ;; key-up is read as a non-editing Escape and deselects the shape.
    (mf/use-effect
     (mf/deps)
     (fn []
       (let [on-key-up (fn [event]
                         (when (kbd/esc? event)
                           (dom/stop-propagation event)
                           ;; With the menu open, Escape only closes it (checked
                           ;; in the DOM: the store may already be cleared).
                           (if (some? (dom/query menu-selector))
                             (st/emit! dw/hide-context-menu)
                             (st/emit! (dw/clear-edition-mode)))))]
         (.addEventListener js/document "keyup" on-key-up)
         #(.removeEventListener js/document "keyup" on-key-up))))

    ;; The IME cursor can move inside the composition without changing its
    ;; text (e.g. switching clauses), which only shows as a selection change.
    (mf/use-effect
     (mf/deps sync-composition-cursor!)
     (fn []
       (.addEventListener js/document "selectionchange" sync-composition-cursor!)
       #(.removeEventListener js/document "selectionchange" sync-composition-cursor!)))

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
       ;; Group the whole editing session (edits, reflow resizes, finalize) into a single
       ;; undo entry. Nested transactions (e.g. style shortcuts) are ref-counted and fold in.
       (st/emit! (dwu/start-undo-transaction shape-id :timeout nil))
       (when (some? (mf/ref-val contenteditable-ref))
         ;; Focus and select all text on mount. `focus-editor!` establishes the
         ;; WASM focus explicitly; select-all is a no-op without it.
         (focus-editor!)
         (text-editor/text-editor-select-all)
         (wasm.api/request-render-preserving-target "text-editor-select-all-on-mount"))
       ;; On unmount, finalize the editor content and then dispose the WASM editor.
       ;; We finalize on unmount instead of relying on the browser blur event, because
       ;; it was not being reliable (timing issues, Firefox issues…)
       (fn []
         (on-blur)
         (st/emit! dw/hide-context-menu
                   (dwu/commit-undo-transaction shape-id))
         (text-editor/text-editor-dispose)
         (wasm.api/request-render-preserving-target "text-editor-dispose"))))

    (mf/use-effect
     (mf/deps)
     (fn []
       (let [timeout-id (atom nil)
             schedule-blink (fn schedule-blink []
                              ;; The caret only blinks for a collapsed cursor. With an active
                              ;; selection there is nothing to animate, so skip the repaint:
                              ;; re-compositing every interval would otherwise redraw the
                              ;; selection over and over (a visible flicker at high zoom).
                              (when (and (text-editor/text-editor-has-focus?)
                                         (not (text-editor/text-editor-has-selection?)))
                                ;; Redraw only the caret (cached frame + overlay) instead of a
                                ;; full `request-render`, which flashes on zoomed-in views by
                                ;; kicking off a progressive tile-by-tile shape re-render.
                                (wasm.api/render-text-editor-overlay!))
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

     [:foreignObject {:x x :y y :width ime-width :height height}
      [:div {:on-click on-click
             :on-double-click on-double-click
             :on-pointer-down on-pointer-down
             :on-pointer-move on-pointer-move
             :on-pointer-up on-pointer-up
             :on-context-menu on-context-menu
             ;; The hover cursor lives here: the capture surface below is
             ;; pointer-events:none so it never receives hover itself.
             :class (dm/str (cur/get-text (:rotation shape) vertical?)
                            " "
                            (stl/css :text-editor))
             :style style}
       [:div {:ref wrapper-ref
              :class (stl/css :ime-anchor)}
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
          :class (input-surface-class)
          :data-testid "text-editor-container"}]]]]]))
