;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.render-wasm.text-editor
  "Text editor WASM bindings"
  (:require
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.serializers.color :as sr-clr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.fonts :as main-fonts]
   ;; Required for side effects: binds the generated enums.
   [app.render-wasm.api.enums]
   [app.render-wasm.api.fonts :as fonts]
   [app.util.color :as uc]
   [app.util.dom :as dom]))

(def multiple-state-multiple (sr/translate-multiple-state :multiple))

(def ^:const TEXT_EDITOR_STYLES_METADATA_SIZE (* 31 4))
(def ^:const TEXT_EDITOR_STYLES_FILL_SOLID 0)
(def ^:const TEXT_EDITOR_STYLES_FILL_LINEAR_GRADIENT 1)
(def ^:const TEXT_EDITOR_STYLES_FILL_RADIAL_GRADIENT 2)
(def ^:const TEXT_EDITOR_STYLES_FILL_IMAGE 3)

(defn- rgba->fill-color
  [rgba]
  (let [rgb (bit-and rgba 0x00ffffff)
        hex (.toString rgb 16)]
    (str "#" (.padStart hex 6 "0"))))

(defn- rgba->opacity
  [rgba]
  (let [alpha (bit-and (bit-shift-right rgba 24) 0xff)]
    (/ (js/Math.round (* (/ alpha 255) 100)) 100)))

(defn- u8->opacity
  [alpha]
  (/ (js/Math.round (* (/ alpha 255) 100)) 100))

(defn- read-fill-from-heap
  [heap-u8 heap-u32 heap-i32 heap-f32 fill-byte-offset]
  (let [fill-type (aget heap-u8 fill-byte-offset)
        fill-u32-offset (mem/->offset-32 fill-byte-offset)]
    (case fill-type
      TEXT_EDITOR_STYLES_FILL_SOLID
      (let [rgba (aget heap-u32 (+ fill-u32-offset 1))]
        {:fill-color (rgba->fill-color rgba)
         :fill-opacity (rgba->opacity rgba)})

      TEXT_EDITOR_STYLES_FILL_LINEAR_GRADIENT
      (let [gradient-u32-offset (mem/->offset-32 (+ fill-byte-offset 4))
            start-x (aget heap-f32 gradient-u32-offset)
            start-y (aget heap-f32 (+ gradient-u32-offset 1))
            end-x (aget heap-f32 (+ gradient-u32-offset 2))
            end-y (aget heap-f32 (+ gradient-u32-offset 3))
            alpha (aget heap-u8 (+ fill-byte-offset 20))
            width (aget heap-f32 (+ gradient-u32-offset 5))
            stop-count (aget heap-u8 (+ fill-byte-offset 28))
            stops (->> (range stop-count)
                       (map (fn [idx]
                              (let [stop-offset (+ fill-byte-offset 32 (* idx 8))
                                    stop-u32-offset (mem/->offset-32 stop-offset)
                                    rgba (aget heap-u32 stop-u32-offset)
                                    offset (aget heap-f32 (+ stop-u32-offset 1))]
                                {:color (rgba->fill-color rgba)
                                 :opacity (rgba->opacity rgba)
                                 :offset (/ (js/Math.round (* offset 100)) 100)})))
                       (into []))]
        {:fill-opacity (u8->opacity alpha)
         :fill-color-gradient {:start-x start-x
                               :start-y start-y
                               :end-x end-x
                               :end-y end-y
                               :width width
                               :stops stops
                               :type :linear}})

      TEXT_EDITOR_STYLES_FILL_RADIAL_GRADIENT
      (let [gradient-u32-offset (mem/->offset-32 (+ fill-byte-offset 4))
            start-x (aget heap-f32 gradient-u32-offset)
            start-y (aget heap-f32 (+ gradient-u32-offset 1))
            end-x (aget heap-f32 (+ gradient-u32-offset 2))
            end-y (aget heap-f32 (+ gradient-u32-offset 3))
            alpha (aget heap-u8 (+ fill-byte-offset 20))
            width (aget heap-f32 (+ gradient-u32-offset 5))
            stop-count (aget heap-u8 (+ fill-byte-offset 28))
            stops (->> (range stop-count)
                       (map (fn [idx]
                              (let [stop-offset (+ fill-byte-offset 32 (* idx 8))
                                    stop-u32-offset (mem/->offset-32 stop-offset)
                                    rgba (aget heap-u32 stop-u32-offset)
                                    offset (aget heap-f32 (+ stop-u32-offset 1))]
                                {:color (rgba->fill-color rgba)
                                 :opacity (rgba->opacity rgba)
                                 :offset (/ (js/Math.round (* offset 100)) 100)})))
                       (into []))]
        {:fill-opacity (u8->opacity alpha)
         :fill-color-gradient {:start-x start-x
                               :start-y start-y
                               :end-x end-x
                               :end-y end-y
                               :width width
                               :stops stops
                               :type :radial}})

      TEXT_EDITOR_STYLES_FILL_IMAGE
      (let [a (aget heap-u32 (+ fill-u32-offset 1))
            b (aget heap-u32 (+ fill-u32-offset 2))
            c (aget heap-u32 (+ fill-u32-offset 3))
            d (aget heap-u32 (+ fill-u32-offset 4))
            alpha (aget heap-u8 (+ fill-byte-offset 20))
            flags (aget heap-u8 (+ fill-byte-offset 21))
            width (aget heap-i32 (+ fill-u32-offset 6))
            height (aget heap-i32 (+ fill-u32-offset 7))]
        {:fill-opacity (u8->opacity alpha)
         :fill-image {:id (uuid/from-unsigned-parts a b c d)
                      :width width
                      :height height
                      :keep-aspect-ratio (not (zero? (bit-and flags 0x01)))
                      :name "sample"}})

      nil)))

(def ^:private selection-color-css-var "--text-editor-selection-background-color")

(defn- resolve-theme-color
  "Resolve a themed CSS color variable (read from the document body) into a
   32-bit argb value for the WASM text editor, preserving the variable's alpha
   channel."
  [css-var]
  (when-let [{:keys [color opacity]}
             (uc/parse-css-color-opacity
              (dom/get-css-variable css-var js/document.body))]
    (sr-clr/hex->u32argb color opacity)))

;; ARGB u32 for opaque white, painted with a Difference blend mode so the caret
;; always shows the inverted color of the background.
(def ^:private caret-invert-color 0xffffffff)

(defn text-editor-apply-theme
  "Push the current theme's selection color (read from the CSS custom properties
   on the document body) into the WASM text editor, together with the default
   caret: white with invert, so it shows the inverted color of the background.
   The caret only switches to a solid text color (invert off) via
   `text-editor-apply-caret-color`. The editor theme is a persistent singleton,
   so call once after init and again on every color-scheme change."
  []
  (when wasm/context-initialized?
    (let [selection (resolve-theme-color selection-color-css-var)]
      (when selection
        (h/call wasm/internal-module "_text_editor_apply_theme" selection caret-invert-color true)))))

(defn- solid-fill?
  [fill]
  (some? (:fill-color fill)))

(defn resolve-caret-color
  "Compute the caret color from the text `fills` at the caret (as returned by
   `text-editor-get-current-styles`), as `{:color <argb-u32> :invert? bool}`:

   - when there is at least one solid fill, match the topmost (visible) one,
     painted normally (`:invert? false`);
   - otherwise (no fill, gradient, image fills, mixed selection, …) use white
     with `:invert? true`, which the renderer paints with a Difference blend so
     the caret is the inverted color of whatever is behind it."
  [fills]
  (if-let [solid (and (sequential? fills)
                      (some #(when (solid-fill? %) %) fills))]
    {:color (sr-clr/hex->u32argb (:fill-color solid) (:fill-opacity solid))
     :invert? false}
    {:color caret-invert-color
     :invert? true}))

(defn text-editor-apply-caret-color
  "Update the WASM text-editor caret color so it matches the text at the caret
   (see `resolve-caret-color`). Re-applies the current theme selection color
   unchanged, since the WASM theme is a singleton holding both."
  [fills]
  (when wasm/context-initialized?
    (let [selection (resolve-theme-color selection-color-css-var)
          {:keys [color invert?]} (resolve-caret-color fills)]
      (when selection
        (h/call wasm/internal-module "_text_editor_apply_theme" selection color invert?)))))

(defn text-editor-focus
  [id]
  (when (wasm/ready?)
    (let [buffer (uuid/get-u32 id)]
      (when-not (h/call wasm/internal-module "_text_editor_focus"
                        (aget buffer 0)
                        (aget buffer 1)
                        (aget buffer 2)
                        (aget buffer 3))
        (throw (js/Error. "TextEditor focus failed"))))))

(defn text-editor-set-cursor-from-offset
  "Sets caret position from shape relative coordinates"
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_set_cursor_from_offset" x y)))

(defn text-editor-set-cursor-from-point
  "Sets caret position from screen (canvas) coordinates"
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_set_cursor_from_point" x y)))

(defn text-editor-toggle-overtype-mode
  "Toggles overtype mode"
  []
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_toggle_overtype_mode")))

(defn text-editor-pointer-down
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_pointer_down" x y)))

(defn text-editor-pointer-down-extend
  "Extends the selection up to the pointer instead of collapsing the caret."
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_pointer_down_extend" x y)))

(defn text-editor-pointer-move
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_pointer_move" x y)))

(defn text-editor-pointer-up
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_pointer_up" x y)))

(defn text-editor-update-blink
  [timestamp-ms]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_update_blink" timestamp-ms)))

(defn text-editor-render-overlay
  []
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_render_overlay")))

(defn text-editor-render-caret
  "Re-compose the frame from the Backbuffer (the last full render) and draw the
   caret/selection overlay on top, submitting one atomic frame. Pixel identical
   to the last full render at any zoom, so the blink does not flash."
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_render_caret")))

(defn text-editor-poll-event
  []
  (when (wasm/ready?)
    (let [res (h/call wasm/internal-module "_text_editor_poll_event")]
      res)))

(defn- text-editor-get-style-property
  ([state value]
   (text-editor-get-style-property state value value))
  ([state value default-value]
   (case state
     0 default-value
     1 value
     2 :multiple
     0)))

(defn- text-editor-compute-font-variant-id
  [font-id font-weight font-style]
  (let [font-data (main-fonts/get-font-data font-id)
        variant (main-fonts/find-closest-variant font-data font-weight font-style)]
    (or (:id variant)
        (:name variant)
        "regular")))

(defn text-editor-get-current-styles
  []
  (when (wasm/ready?)
    (let [ptr (h/call wasm/internal-module "_text_editor_get_current_styles")]
      (when (and ptr (not (zero? ptr)))
        (let [heap-u8 (mem/get-heap-u8)
              heap-u32 (mem/get-heap-u32)
              heap-i32 (mem/get-heap-i32)
              heap-f32 (mem/get-heap-f32)
              u32-offset (mem/->offset-32 ptr)
              vertical-align   (aget heap-u32 u32-offset)
              text-align-state (aget heap-u32 (+ u32-offset 1))
              text-direction-state (aget heap-u32 (+ u32-offset 2))
              text-decoration-state (aget heap-u32 (+ u32-offset 3))
              text-transform-state (aget heap-u32 (+ u32-offset 4))
              font-family-id-state (aget heap-u32 (+ u32-offset 5))
              font-size-state (aget heap-u32 (+ u32-offset 6))
              font-weight-state (aget heap-u32 (+ u32-offset 7))
              ;; Unused: the variant id is stored as a zero uuid for every span
              _font-variant-id-state (aget heap-u32 (+ u32-offset 8))
              line-height-state (aget heap-u32 (+ u32-offset 9))
              letter-spacing-state (aget heap-u32 (+ u32-offset 10))
              font-style-state (aget heap-u32 (+ u32-offset 11))
              num-fills (aget heap-u32 (+ u32-offset 12))
              multiple-fills (aget heap-u32 (+ u32-offset 13))

              text-align-value (aget heap-u32 (+ u32-offset 14))
              text-direction-value (aget heap-u32 (+ u32-offset 15))
              text-decoration-value (aget heap-u32 (+ u32-offset 16))
              text-transform-value (aget heap-u32 (+ u32-offset 17))
              font-family-id-a (aget heap-u32 (+ u32-offset 18))
              font-family-id-b (aget heap-u32 (+ u32-offset 19))
              font-family-id-c (aget heap-u32 (+ u32-offset 20))
              font-family-id-d (aget heap-u32 (+ u32-offset 21))
              font-family-id-value (uuid/from-unsigned-parts font-family-id-a font-family-id-b font-family-id-c font-family-id-d)
              font-style-raw-value (aget heap-u32 (+ u32-offset 22))
              font-size-value (aget heap-f32 (+ u32-offset 23))
              font-weight-value (aget heap-i32 (+ u32-offset 24))
              line-height-value (aget heap-f32 (+ u32-offset 29))
              letter-spacing-value (aget heap-f32 (+ u32-offset 30))
              font-id (fonts/uuid->font-id font-family-id-value)
              font-style-value (sr/untranslate-font-style (text-editor-get-style-property font-style-state font-style-raw-value))
              font-variant-id-computed (text-editor-compute-font-variant-id font-id font-weight-value font-style-value)
              ;; A font variant is defined by its family + weight + style, so it
              ;; is "mixed" when any of those is mixed. When the family itself is
              ;; mixed there is no single font to resolve variants against, so we
              ;; also report the variant as mixed.
              font-variant-multiple? (or (= font-family-id-state multiple-state-multiple)
                                         (= font-weight-state multiple-state-multiple)
                                         (= font-style-state multiple-state-multiple))

              fills (->> (range num-fills)
                         (map (fn [idx]
                                (read-fill-from-heap
                                 heap-u8 heap-u32 heap-i32 heap-f32
                                 (+ ptr
                                    TEXT_EDITOR_STYLES_METADATA_SIZE
                                    (* idx types.fills.impl/FILL-U8-SIZE)))))
                         (filter some?)
                         (into []))

              ;; The order of these two variables is important, do not
              ;; reorder them.
              selected-colors (if (= multiple-fills 1) fills nil)
              fills (if (= multiple-fills 1) :multiple fills)

              result {:vertical-align (sr/untranslate-vertical-align vertical-align)
                      :text-align (sr/untranslate-text-align (text-editor-get-style-property text-align-state text-align-value))
                      :text-direction (sr/untranslate-text-direction (text-editor-get-style-property text-direction-state text-direction-value))
                      :text-decoration (sr/untranslate-text-decoration (text-editor-get-style-property text-decoration-state text-decoration-value))
                      :text-transform (sr/untranslate-text-transform (text-editor-get-style-property text-transform-state text-transform-value))
                      ;; WASM reports size/weight as numbers, but the rest of Penpot (and the backend schema) expects strings.
                      :line-height (let [height (text-editor-get-style-property line-height-state line-height-value)]
                                     (if (= height :multiple) height (str height)))
                      :letter-spacing (let [spacing (text-editor-get-style-property letter-spacing-state letter-spacing-value)]
                                        (if (= spacing :multiple) spacing (str spacing)))
                      :font-size (let [size (text-editor-get-style-property font-size-state font-size-value)]
                                   (if (= size :multiple) size (str size)))
                      :font-weight (let [weight (text-editor-get-style-property font-weight-state font-weight-value)]
                                     (if (= weight :multiple) weight (str weight)))
                      :font-style font-style-value
                      :font-family (text-editor-get-style-property font-family-id-state font-id)
                      :font-id (text-editor-get-style-property font-family-id-state font-id)
                      :font-variant-id (if font-variant-multiple? :multiple font-variant-id-computed)
                      :typography-ref-file nil
                      :typography-ref-id nil
                      :selected-colors selected-colors
                      :fills fills}]

          (mem/free)
          result)))))

(defn text-editor-encode-text-pre
  [text]
  (when (and (not (empty? text))
             (wasm/ready?))
    (let [encoder (js/TextEncoder.)
          buf (.encode encoder text)
          heapu8 (mem/get-heap-u8)
          size (mem/size buf)
          offset (mem/alloc size)]
      (mem/write-buffer offset heapu8 buf))))

(defn text-editor-encode-text-post
  [text]
  (when (and (not (empty? text))
             (wasm/ready?))
    (mem/free)))

(defn text-editor-composition-start
  []
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_composition_start")))

(defn text-editor-composition-update
  [text]
  (when (wasm/ready?)
    (text-editor-encode-text-pre text)
    (h/call wasm/internal-module "_text_editor_composition_update")
    (text-editor-encode-text-post text)))

(defn text-editor-composition-end
  [text]
  (when (wasm/ready?)
    (text-editor-encode-text-pre text)
    (h/call wasm/internal-module "_text_editor_composition_end")
    (text-editor-encode-text-post text)))

(defn text-editor-insert-text
  [text]
  (when (wasm/ready?)
    (text-editor-encode-text-pre text)
    (h/call wasm/internal-module "_text_editor_insert_text")
    (text-editor-encode-text-post text)))

(defn text-editor-delete-backward
  ([]
   (text-editor-delete-backward false))
  ([word-boundary]
   (when (wasm/ready?)
     (h/call wasm/internal-module "_text_editor_delete_backward" word-boundary))))

(defn text-editor-delete-forward
  ([]
   (text-editor-delete-forward false))
  ([word-boundary]
   (when (wasm/ready?)
     (h/call wasm/internal-module "_text_editor_delete_forward" word-boundary))))

(defn text-editor-insert-paragraph []
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_insert_paragraph")))

(defn text-editor-move-cursor
  [direction word-boundary extend-selection]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_move_cursor" direction word-boundary (if extend-selection 1 0))))

(defn text-editor-select-all
  []
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_select_all")))

(defn text-editor-select-word-boundary
  [{:keys [x y]}]
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_select_word_boundary" x y)))

(defn text-editor-blur
  []
  (when (wasm/ready?)
    (when-not (h/call wasm/internal-module "_text_editor_blur")
      (throw (js/Error. "TextEditor blur failed")))))

(defn text-editor-dispose
  []
  (when (wasm/ready?)
    (h/call wasm/internal-module "_text_editor_dispose")))

(defn text-editor-has-focus?
  ([id]
   (when (wasm/ready?)
     (not (zero? (h/call wasm/internal-module "_text_editor_has_focus_with_id" id)))))
  ([]
   (when (wasm/ready?)
     (not (zero? (h/call wasm/internal-module "_text_editor_has_focus"))))))

(defn text-editor-has-selection?
  ([]
   (when (wasm/ready?)
     (not (zero? (h/call wasm/internal-module "_text_editor_has_selection"))))))

(defn text-editor-export-content
  []
  (when (wasm/ready?)
    (let [ptr (h/call wasm/internal-module "_text_editor_export_content")]
      (when (and ptr (not (zero? ptr)))
        (let [json-str (mem/read-string ptr)]
          (mem/free)
          (js/JSON.parse json-str))))))

(defn text-editor-export-selection
  "Export only the currently selected text as plain text from the WASM editor. Requires WASM support (_text_editor_export_selection)."
  []
  (when (wasm/ready?)
    (let [ptr (h/call wasm/internal-module "_text_editor_export_selection")]
      (when (and ptr (not (zero? ptr)))
        (let [text (mem/read-string ptr)]
          (mem/free)
          text)))))

(defn text-editor-get-active-shape-id
  []
  (when (wasm/ready?)
    (try
      (let [byte-offset (mem/alloc 16)
            u32-offset (mem/->offset-32 byte-offset)
            heap (mem/get-heap-u32)]
        (h/call wasm/internal-module "_text_editor_get_active_shape_id" byte-offset)
        (let [a (aget heap u32-offset)
              b (aget heap (+ u32-offset 1))
              c (aget heap (+ u32-offset 2))
              d (aget heap (+ u32-offset 3))
              result (when (or (not= a 0) (not= b 0) (not= c 0) (not= d 0))
                       (uuid/from-unsigned-parts a b c d))]
          (mem/free)
          result))
      (catch js/Error e
        (js/console.error "[text-editor-get-active-shape-id] Error:" e)
        nil))))

(defn text-editor-get-selection
  []
  (when (wasm/ready?)
    (let [byte-offset     (mem/alloc 16)
          u32-offset      (mem/->offset-32 byte-offset)
          heap            (mem/get-heap-u32)
          has-selection?  (h/call wasm/internal-module "_text_editor_get_selection" byte-offset)]
      (if has-selection?
        (let [result {:anchor-para   (aget heap u32-offset)
                      :anchor-offset (aget heap (+ u32-offset 1))
                      :focus-para    (aget heap (+ u32-offset 2))
                      :focus-offset  (aget heap (+ u32-offset 3))}]
          (mem/free)
          result)
        (do
          (mem/free)
          nil)))))

;; This is used as a intermediate cache between Clojure global state and WASM state.
(def ^:private shape-text-contents (atom {}))

(defn cache-shape-text-content!
  [shape-id content]
  (when (some? content)
    (swap! shape-text-contents assoc shape-id content)))

(defn get-cached-content
  [shape-id]
  (get @shape-text-contents shape-id))

(defn update-cached-content!
  [shape-id content]
  (swap! shape-text-contents assoc shape-id content))

;; Typography chosen at a collapsed caret: not applied to existing text, but
;; picked up (as a new span) by the next inserted text. Keyed by shape-id.
(def ^:private pending-caret-styles (atom {}))

(defn merge-pending-caret-styles!
  "Stack `styles` onto the shape's pending caret style."
  [shape-id styles]
  (swap! pending-caret-styles update shape-id merge styles))

(defn get-pending-caret-styles
  [shape-id]
  (get @pending-caret-styles shape-id))

(defn clear-pending-caret-styles!
  "Drop every pending caret style; only the active shape can hold one."
  []
  (reset! pending-caret-styles {}))

(defn- merge-exported-texts-into-content
  "Merge exported span texts back into the existing content tree.

  The WASM editor may split or merge paragraphs (Enter / Backspace at
  paragraph boundary), so the exported structure can differ from the
  original.  When extra paragraphs or spans appear we clone styling from
  the nearest existing sibling; when fewer appear we truncate.

  exported-texts  vector of vectors  [[\"span1\" \"span2\"] [\"p2s1\"]]
  content         existing Penpot content map (root -> paragraph-set -> …)"
  [content exported-texts]
  (let [para-set       (first (get content :children))
        orig-paras     (get para-set :children)
        num-orig       (count orig-paras)
        last-orig-para (when (seq orig-paras) (last orig-paras))
        template-span  (when last-orig-para
                         (-> last-orig-para :children last))
        new-paras
        (mapv (fn [para-idx exported-span-texts]
                (let [orig-para (if (< para-idx num-orig)
                                  (nth orig-paras para-idx)
                                  (dissoc last-orig-para :children))
                      orig-spans     (get orig-para :children)
                      num-orig-spans (count orig-spans)
                      last-orig-span (when (seq orig-spans) (last orig-spans))]
                  (assoc orig-para :children
                         (mapv (fn [span-idx new-text]
                                 (let [orig-span (if (< span-idx num-orig-spans)
                                                   (nth orig-spans span-idx)
                                                   (or last-orig-span template-span))]
                                   (assoc orig-span :text new-text)))
                               (range (count exported-span-texts))
                               exported-span-texts))))
              (range (count exported-texts))
              exported-texts)
        new-para-set (assoc para-set :children new-paras)]
    (assoc content :children [new-para-set])))

(defn- default-empty-text-content
  "Build a default, empty text content tree used as a merge template.

  A text shape created by a single click starts with `:content` nil, so
  `set-shape-text-content` never seeds the content cache for it. Without a
  template `text-editor-sync-content` would bail and the characters typed into
  the WASM editor would never reach the shape. This provides the default
  (Source Sans Pro) styling the WASM editor uses for a fresh empty shape."
  []
  (let [attrs (txt/get-default-text-attrs)]
    {:type "root"
     :children [{:type "paragraph-set"
                 :children [(merge attrs
                                   {:type "paragraph"
                                    :children [(merge attrs {:text ""})]})]}]}))

(defn text-editor-sync-content
  "Sync text content from the WASM text editor back to the frontend shape.

  Exports the current span texts from WASM, merges them into the shape's
  cached content tree (preserving per-span styling), and returns the
  shape-id and the fully merged content map ready for
  v2-update-text-shape-content."
  []
  (when (and (wasm/ready?) (text-editor-has-focus?))
    (let [shape-id  (text-editor-get-active-shape-id)
          new-texts (text-editor-export-content)]
      (when (and shape-id new-texts)
        (let [texts-clj (js->clj new-texts)
              ;; A brand-new empty text shape (single click) has no cached
              ;; content yet, so fall back to a default template so the first
              ;; keystrokes are synced back to the shape instead of dropped.
              content   (or (get-cached-content shape-id)
                            (default-empty-text-content))]
          (when content
            (let [merged (merge-exported-texts-into-content content texts-clj)]
              (swap! shape-text-contents assoc shape-id merged)
              {:shape-id shape-id
               :content  merged})))))))

(defn- normalize-selection
  "Given anchor/focus para+offset, return {:start-para :start-offset :end-para :end-offset}
   ordered so start <= end."
  [{:keys [anchor-para anchor-offset focus-para focus-offset]}]
  (if (or (< anchor-para focus-para)
          (and (= anchor-para focus-para) (<= anchor-offset focus-offset)))
    {:start-para anchor-para :start-offset anchor-offset
     :end-para focus-para :end-offset focus-offset}
    {:start-para focus-para :start-offset focus-offset
     :end-para anchor-para :end-offset anchor-offset}))

(defn apply-attrs-to-paragraph
  "Apply `styles` (attrs map, or a fn per span) within [sel-start, sel-end), splitting spans."
  [para sel-start sel-end styles]
  (let [spans  (:children para)

        result (loop [spans spans
                      pos   0
                      acc   []]
                 (if (empty? spans)
                   acc
                   (let [span      (first spans)
                         text      (:text span)
                         span-len  (count text)
                         span-end  (+ pos span-len)
                         ol-start  (max pos sel-start)
                         ol-end    (min span-end sel-end)
                         has-overlap? (< ol-start ol-end)]
                     (if (not has-overlap?)
                       (recur (rest spans) span-end (conj acc span))
                       (let [before   (when (> ol-start pos)
                                        (assoc span :text (subs text 0 (- ol-start pos))))
                             selected (-> (if (fn? styles)
                                            (styles span)
                                            (merge span styles))
                                          (assoc :text (subs text (- ol-start pos) (- ol-end pos))))
                             after    (when (< ol-end span-end)
                                        (assoc span :text (subs text (- ol-end pos))))]
                         (recur (rest spans) span-end
                                (-> acc
                                    (into (keep identity [before selected after])))))))))]
    (assoc para :children result)))

(defn- para-char-count
  [para]
  (apply + (map (fn [span] (count (:text span))) (:children para))))

(defn- paragraph-selected-spans
  "Return the spans of `para` that overlap the [sel-start, sel-end) char range."
  [para sel-start sel-end]
  (loop [spans (:children para)
         pos   0
         acc   []]
    (if (empty? spans)
      acc
      (let [span     (first spans)
            span-end (+ pos (count (:text span)))
            overlap? (< (max pos sel-start) (min span-end sel-end))]
        (recur (rest spans) span-end (cond-> acc overlap? (conj span)))))))

(defn selection-fills
  "The selection's fills: shared vector if all spans match, `:multiple` if not, nil if empty."
  [content {:keys [start-para start-offset end-para end-offset]}]
  (let [paragraphs (:children (first (:children content)))
        selected   (mapcat (fn [idx para]
                             (cond
                               (or (< idx start-para) (> idx end-para)) nil
                               (= start-para end-para) (paragraph-selected-spans para start-offset end-offset)
                               (= idx start-para)      (paragraph-selected-spans para start-offset (para-char-count para))
                               (= idx end-para)        (paragraph-selected-spans para 0 end-offset)
                               :else                   (paragraph-selected-spans para 0 (para-char-count para))))
                           (range (count paragraphs))
                           paragraphs)
        fills-set  (into #{} (map :fills) selected)]
    (cond
      (empty? selected)       nil
      (= 1 (count fills-set)) (first fills-set)
      :else                   :multiple)))

(defn- apply-styles-over-range
  "Apply `styles` (attrs map or per-span fn) to the char range of `content`, splitting spans."
  [content {:keys [start-para start-offset end-para end-offset]} styles]
  (let [paragraph-set  (first (:children content))
        paragraphs     (:children paragraph-set)
        new-paragraphs (mapv (fn [idx para]
                               (cond
                                 ;; paragraph outside the range of paragraphs.
                                 (or (< idx start-para) (> idx end-para))
                                 para

                                 ;; same paragraph.
                                 (= start-para end-para)
                                 (apply-attrs-to-paragraph para start-offset end-offset styles)

                                 ;; first paragraph
                                 (= idx start-para)
                                 (apply-attrs-to-paragraph para start-offset (para-char-count para) styles)

                                 ;; final paragraph
                                 (= idx end-para)
                                 (apply-attrs-to-paragraph para 0 end-offset styles)

                                 ;; any other paragraph
                                 :else
                                 (apply-attrs-to-paragraph para 0 (para-char-count para) styles)))
                             (range (count paragraphs))
                             paragraphs)]
    (assoc content :children [(assoc paragraph-set :children new-paragraphs)])))

(defn- clean-styles
  "Drop nil-valued attrs (unlike the DOM path, our merge would keep them and fail
   the backend schema); a per-span fn is passed through untouched."
  [styles]
  (if (fn? styles)
    styles
    (into {} (remove (comp nil? val)) styles)))

(defn apply-styles-to-selection
  "Apply `styles` (attrs map, or a fn per span) to the selected spans; `:with-fills?` also returns `:fills`."
  [styles use-shape-fn set-shape-text-content-fn & [{:keys [with-fills?]}]]
  (when (wasm/ready?)
    (let [styles    (clean-styles styles)
          shape-id  (text-editor-get-active-shape-id)
          selection (text-editor-get-selection)]

      (when (and shape-id selection)
        (let [content (get-cached-content shape-id)]
          (when content
            (let [normalized-selection (normalize-selection selection)
                  {:keys [start-para start-offset end-para end-offset]} normalized-selection

                  collapsed?  (and (= start-para end-para) (= start-offset end-offset))

                  new-content (when (not collapsed?)
                                (apply-styles-over-range content normalized-selection styles))]

              (when new-content
                (update-cached-content! shape-id new-content)
                (use-shape-fn shape-id)
                (set-shape-text-content-fn shape-id new-content)
                (cond-> {:shape-id shape-id
                         :content  new-content}
                  with-fills?
                  (assoc :fills (selection-fills new-content normalized-selection)))))))))))

(defn apply-styles-to-range
  "Like `apply-styles-to-selection` but over an explicit range (used to restyle
   just-inserted text); returns `{:shape-id :content}` or nil."
  [shape-id {:keys [start-para start-offset end-para end-offset] :as range} styles
   use-shape-fn set-shape-text-content-fn]
  (when (wasm/ready?)
    (let [styles  (clean-styles styles)
          content (get-cached-content shape-id)]
      (when (and content
                 (seq styles)
                 (not (and (= start-para end-para) (= start-offset end-offset))))
        (let [new-content (apply-styles-over-range content range styles)]
          (update-cached-content! shape-id new-content)
          (use-shape-fn shape-id)
          (set-shape-text-content-fn shape-id new-content)
          {:shape-id shape-id
           :content  new-content})))))

(defn apply-paragraph-attrs-to-selection
  "Apply paragraph level attrs (text-align, text-direction) to the whole
   paragraphs the editor selection touches; a collapsed caret means just the one
   it sits in."
  [attrs use-shape-fn set-shape-text-content-fn]
  (when (wasm/ready?)
    (let [shape-id  (text-editor-get-active-shape-id)
          selection (text-editor-get-selection)]
      (when (and shape-id selection)
        (when-let [content (get-cached-content shape-id)]
          (let [{:keys [start-para end-para]} (normalize-selection selection)
                paragraph-set  (first (:children content))
                new-paragraphs (into []
                                     (map-indexed (fn [idx para]
                                                    (if (<= start-para idx end-para)
                                                      (merge para attrs)
                                                      para)))
                                     (:children paragraph-set))
                new-content    (assoc content :children
                                      [(assoc paragraph-set :children new-paragraphs)])]
            (update-cached-content! shape-id new-content)
            (use-shape-fn shape-id)
            (set-shape-text-content-fn shape-id new-content)
            {:shape-id shape-id
             :content  new-content}))))))
