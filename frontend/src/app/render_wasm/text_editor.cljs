;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.text-editor
  "Text editor WASM bindings"
  (:require
   [app.common.data :as d]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.fonts :as main-fonts]
   [app.render-wasm.api.fonts :as fonts]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.wasm :as wasm]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [cuerdas.core :as str]))

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
(def ^:private caret-color-css-var "--text-editor-caret-color")

(defn- resolve-theme-color
  "Resolve a themed CSS color variable (read from the document body) into a
   32-bit argb value for the WASM text editor, preserving the variable's alpha
   channel."
  [css-var]
  (when-let [{:keys [color opacity]}
             (uc/parse-css-color-opacity
              (dom/get-css-variable css-var js/document.body))]
    (sr-clr/hex->u32argb color opacity)))

(defn text-editor-apply-theme
  "Push the current theme's selection and caret colors (read from the CSS
   custom properties on the document body) into the WASM text editor. The
   editor theme is a persistent singleton, so call once after init and again
   on every color-scheme change."
  []
  (when wasm/context-initialized?
    (let [selection (resolve-theme-color selection-color-css-var)
          caret     (resolve-theme-color caret-color-css-var)]
      (when (and selection caret)
        (h/call wasm/internal-module "_text_editor_apply_theme" selection caret)))))

(defn text-editor-focus
  [id]
  (when wasm/context-initialized?
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
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_set_cursor_from_offset" x y)))

(defn text-editor-set-cursor-from-point
  "Sets caret position from screen (canvas) coordinates"
  [{:keys [x y]}]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_set_cursor_from_point" x y)))

(defn text-editor-toggle-overtype-mode
  "Toggles overtype mode"
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_toggle_overtype_mode")))

(defn text-editor-pointer-down
  [{:keys [x y]}]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_pointer_down" x y)))

(defn text-editor-pointer-move
  [{:keys [x y]}]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_pointer_move" x y)))

(defn text-editor-pointer-up
  [{:keys [x y]}]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_pointer_up" x y)))

(defn text-editor-update-blink
  [timestamp-ms]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_update_blink" timestamp-ms)))

(defn text-editor-render-overlay
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_render_overlay")))

(defn text-editor-poll-event
  []
  (when wasm/context-initialized?
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

(defn- text-editor-translate-vertical-align
  [vertical-align]
  (case vertical-align
    0 "top"
    1 "center"
    2 "bottom"))

(defn- text-editor-translate-text-align
  [text-align]
  (case text-align
    0 "left"
    1 "center"
    2 "right"
    text-align))

(defn- text-editor-translate-text-direction
  [text-direction]
  (case text-direction
    0 "ltr"
    1 "rtl"
    text-direction))

(defn- text-editor-translate-text-transform
  [text-transform]
  (case text-transform
    0 "none"
    1 "lowercase"
    2 "uppercase"
    3 "capitalize"
    text-transform))

(defn- text-editor-translate-text-decoration
  [text-decoration]
  (case text-decoration
    0 "none"
    1 "underline"
    2 "linethrough"
    3 "overline"
    text-decoration))

(defn- text-editor-translate-font-style
  [font-style]
  (case font-style
    0 "normal"
    1 "italic"
    font-style))

(defn- text-editor-compute-font-variant-id
  [font-id font-weight font-style]
  (let [font-data (main-fonts/get-font-data font-id)
        variant (main-fonts/find-closest-variant font-data font-weight font-style)]
    (or (:id variant)
        (:name variant)
        "regular")))

(defn text-editor-get-current-styles
  []
  (when wasm/context-initialized?
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
              font-style-value (text-editor-translate-font-style (text-editor-get-style-property font-style-state font-style-raw-value))
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

              result {:vertical-align (text-editor-translate-vertical-align vertical-align)
                      :text-align (text-editor-translate-text-align (text-editor-get-style-property text-align-state text-align-value))
                      :text-direction (text-editor-translate-text-direction (text-editor-get-style-property text-direction-state text-direction-value))
                      :text-decoration (text-editor-translate-text-decoration (text-editor-get-style-property text-decoration-state text-decoration-value))
                      :text-transform (text-editor-translate-text-transform (text-editor-get-style-property text-transform-state text-transform-value))
                      :line-height (text-editor-get-style-property line-height-state line-height-value)
                      :letter-spacing (text-editor-get-style-property letter-spacing-state letter-spacing-value)
                      :font-size (text-editor-get-style-property font-size-state font-size-value)
                      :font-weight (text-editor-get-style-property font-weight-state font-weight-value)
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
             wasm/context-initialized?)
    (let [encoder (js/TextEncoder.)
          buf (.encode encoder text)
          heapu8 (mem/get-heap-u8)
          size (mem/size buf)
          offset (mem/alloc size)]
      (mem/write-buffer offset heapu8 buf))))

(defn text-editor-encode-text-post
  [text]
  (when (and (not (empty? text))
             wasm/context-initialized?)
    (mem/free)))

(defn text-editor-composition-start
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_composition_start")))

(defn text-editor-composition-update
  [text]
  (when wasm/context-initialized?
    (text-editor-encode-text-pre text)
    (h/call wasm/internal-module "_text_editor_composition_update")
    (text-editor-encode-text-post text)))

(defn text-editor-composition-end
  [text]
  (when wasm/context-initialized?
    (text-editor-encode-text-pre text)
    (h/call wasm/internal-module "_text_editor_composition_end")
    (text-editor-encode-text-post text)))

(defn text-editor-insert-text
  [text]
  (when wasm/context-initialized?
    (text-editor-encode-text-pre text)
    (h/call wasm/internal-module "_text_editor_insert_text")
    (text-editor-encode-text-post text)))

(defn text-editor-delete-backward
  ([]
   (text-editor-delete-backward false))
  ([word-boundary]
   (when wasm/context-initialized?
     (h/call wasm/internal-module "_text_editor_delete_backward" word-boundary))))

(defn text-editor-delete-forward
  ([]
   (text-editor-delete-forward false))
  ([word-boundary]
   (when wasm/context-initialized?
     (h/call wasm/internal-module "_text_editor_delete_forward" word-boundary))))

(defn text-editor-insert-paragraph []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_insert_paragraph")))

(defn text-editor-move-cursor
  [direction word-boundary extend-selection]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_move_cursor" direction word-boundary (if extend-selection 1 0))))

(defn text-editor-select-all
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_select_all")))

(defn text-editor-select-word-boundary
  [{:keys [x y]}]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_select_word_boundary" x y)))

(defn text-editor-blur
  []
  (when wasm/context-initialized?
    (when-not (h/call wasm/internal-module "_text_editor_blur")
      (throw (js/Error. "TextEditor blur failed")))))

(defn text-editor-dispose
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_dispose")))

(defn text-editor-has-focus?
  ([id]
   (when wasm/context-initialized?
     (not (zero? (h/call wasm/internal-module "_text_editor_has_focus_with_id" id)))))
  ([]
   (when wasm/context-initialized?
     (not (zero? (h/call wasm/internal-module "_text_editor_has_focus"))))))

(defn text-editor-has-selection?
  ([]
   (when wasm/context-initialized?
     (not (zero? (h/call wasm/internal-module "_text_editor_has_selection"))))))

(defn text-editor-export-content
  []
  (when wasm/context-initialized?
    (let [ptr (h/call wasm/internal-module "_text_editor_export_content")]
      (when (and ptr (not (zero? ptr)))
        (let [json-str (mem/read-string ptr)]
          (mem/free)
          (js/JSON.parse json-str))))))

(defn text-editor-export-selection
  "Export only the currently selected text as plain text from the WASM editor. Requires WASM support (_text_editor_export_selection)."
  []
  (when wasm/context-initialized?
    (let [ptr (h/call wasm/internal-module "_text_editor_export_selection")]
      (when (and ptr (not (zero? ptr)))
        (let [text (mem/read-string ptr)]
          (mem/free)
          text)))))

(defn text-editor-get-active-shape-id
  []
  (when wasm/context-initialized?
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
  (when wasm/context-initialized?
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

(defn- normalize-exported-paragraph
  "Accept either legacy [[span…]] export rows or the richer
  {:spans […] :list-style … :list-indent … :list-style-position …} maps."
  [exported]
  (cond
    (map? exported)
    {:spans (vec (get exported "spans" (get exported :spans)))
     :list-style (or (get exported "listStyle")
                     (get exported :list-style)
                     "none")
     :list-indent (or (get exported "listIndent")
                      (get exported :list-indent)
                      0)
     :list-style-position (or (get exported "listStylePosition")
                              (get exported :list-style-position)
                              txt/default-list-style-position)}

    (sequential? exported)
    {:spans (vec exported)
     :list-style nil
     :list-indent nil
     :list-style-position nil}

    :else
    {:spans [""]
     :list-style nil
     :list-indent nil
     :list-style-position nil}))

(defn- merge-exported-texts-into-content
  "Merge exported span texts back into the existing content tree.

  The WASM editor may split or merge paragraphs (Enter / Backspace at
  paragraph boundary), so the exported structure can differ from the
  original.  When extra paragraphs or spans appear we clone styling from
  the nearest existing sibling; when fewer appear we truncate.

  exported-texts  vector of paragraph exports (legacy span vectors or maps)
  content         existing Penpot content map (root -> paragraph-set -> …)"
  [content exported-texts]
  (let [para-set       (first (get content :children))
        orig-paras     (get para-set :children)
        num-orig       (count orig-paras)
        last-orig-para (when (seq orig-paras) (last orig-paras))
        template-span  (when last-orig-para
                         (-> last-orig-para :children last))
        new-paras
        (mapv (fn [para-idx exported]
                (let [{:keys [spans list-style list-indent list-style-position]} (normalize-exported-paragraph exported)
                      orig-para (if (< para-idx num-orig)
                                  (nth orig-paras para-idx)
                                  (dissoc last-orig-para :children))
                      orig-spans     (get orig-para :children)
                      num-orig-spans (count orig-spans)
                      last-orig-span (when (seq orig-spans) (last orig-spans))
                      para-with-spans
                      (assoc orig-para :children
                             (mapv (fn [span-idx new-text]
                                     (let [orig-span (if (< span-idx num-orig-spans)
                                                       (nth orig-spans span-idx)
                                                       (or last-orig-span template-span))]
                                       (assoc orig-span :text new-text)))
                                   (range (count spans))
                                   spans))]
                  (cond-> para-with-spans
                    (some? list-style)
                    (assoc :list-style list-style)

                    (some? list-indent)
                    (assoc :list-indent (txt/clamp-list-indent list-indent))

                    (some? list-style-position)
                    (assoc :list-style-position (txt/normalize-list-style-position list-style-position)))))
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
  (when (and wasm/context-initialized? (text-editor-has-focus?))
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

(defn- apply-attrs-to-paragraph
  "Apply attrs to spans within [sel-start, sel-end) char range of a single paragraph.
   Splits spans at boundaries as needed."
  [para sel-start sel-end attrs]
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
                             selected (merge span attrs
                                             {:text (subs text (- ol-start pos) (- ol-end pos))})
                             after    (when (< ol-end span-end)
                                        (assoc span :text (subs text (- ol-end pos))))]
                         (recur (rest spans) span-end
                                (-> acc
                                    (into (keep identity [before selected after])))))))))]
    (assoc para :children result)))

(defn- para-char-count
  [para]
  (apply + (map (fn [span] (count (:text span))) (:children para))))

(defn- apply-attrs-to-all-paragraphs
  [content attrs]
  (let [paragraph-set (first (:children content))
        new-paragraphs
        (mapv (fn [para]
                (update para :children
                        (fn [spans]
                          (mapv #(merge % attrs) spans))))
              (:children paragraph-set))]
    (assoc content :children [(assoc paragraph-set :children new-paragraphs)])))

(defn- editor-content-for-style-update
  "Prefer live WASM text merged into the cached content tree."
  [shape-id]
  (or (:content (text-editor-sync-content))
      (get-cached-content shape-id)))

(defn apply-text-attrs-to-all
  "Apply span-level attrs to every text run in the active WASM editor shape."
  [attrs use-shape-fn set-shape-text-content-fn]
  (when wasm/context-initialized?
    (let [shape-id (text-editor-get-active-shape-id)]
      (when shape-id
        (when-let [content (editor-content-for-style-update shape-id)]
          (let [new-content (apply-attrs-to-all-paragraphs content attrs)]
            (update-cached-content! shape-id new-content)
            (use-shape-fn shape-id)
            (set-shape-text-content-fn shape-id new-content)
            {:shape-id shape-id
             :content  new-content}))))))

(defn apply-styles-to-selection
  [attrs use-shape-fn set-shape-text-content-fn]
  (when wasm/context-initialized?
    (let [shape-id  (text-editor-get-active-shape-id)
          selection (text-editor-get-selection)]

      (when (and shape-id selection)
        (when-let [content (editor-content-for-style-update shape-id)]
          (let [normalized-selection (normalize-selection selection)
                {:keys [start-para start-offset end-para end-offset]} normalized-selection

                collapsed?      (and (= start-para end-para) (= start-offset end-offset))

                paragraph-set   (first (:children content))
                paragraphs      (:children paragraph-set)

                new-paragraphs
                (if collapsed?
                  (:children (first (apply-attrs-to-all-paragraphs content attrs)))
                  (mapv (fn [idx para]
                          (cond
                            (or (< idx start-para) (> idx end-para))
                            para

                            (= start-para end-para)
                            (apply-attrs-to-paragraph para start-offset end-offset attrs)

                            (= idx start-para)
                            (apply-attrs-to-paragraph para start-offset (para-char-count para) attrs)

                            (= idx end-para)
                            (apply-attrs-to-paragraph para 0 end-offset attrs)

                            :else
                            (apply-attrs-to-paragraph para 0 (para-char-count para) attrs)))

                        (range (count paragraphs))
                        paragraphs))

                new-content
                (if collapsed?
                  (apply-attrs-to-all-paragraphs content attrs)
                  (assoc content :children
                         [(assoc paragraph-set :children new-paragraphs)]))]

            (update-cached-content! shape-id new-content)
            (use-shape-fn shape-id)
            (set-shape-text-content-fn shape-id new-content)
            {:shape-id shape-id
             :content  new-content}))))))

(defn- selection-paragraph-range
  "Return inclusive [start end] paragraph indices for the current caret/selection.
   Falls back to the whole content when there is no active editor selection."
  [paragraphs]
  (let [selection (text-editor-get-selection)
        last-idx  (max 0 (dec (count paragraphs)))]
    (if selection
      (let [{:keys [start-para end-para]} (normalize-selection selection)]
        [(max 0 (min start-para end-para))
         (min last-idx (max start-para end-para))])
      [0 last-idx])))

(defn current-list-values
  "Read list-style / list-indent / list-style-position for the active caret/selection paragraphs."
  []
  (when wasm/context-initialized?
    (when-let [shape-id (text-editor-get-active-shape-id)]
      (when-let [content (get-cached-content shape-id)]
        (let [paragraphs (-> content :children first :children)
              [start end] (selection-paragraph-range paragraphs)
              selected    (subvec (vec paragraphs) start (inc end))
              styles      (into #{} (map #(d/nilv (:list-style %) "none")) selected)
              indents     (into #{} (map #(d/nilv (:list-indent %) 0)) selected)
              positions   (into #{} (map #(txt/normalize-list-style-position
                                           (:list-style-position %)))
                                selected)]
          {:list-style (if (= 1 (count styles)) (first styles) :multiple)
           :list-indent (if (= 1 (count indents)) (first indents) :multiple)
           :list-style-position (if (= 1 (count positions)) (first positions) :multiple)})))))

(defn apply-list-attrs-to-selection
  "Apply paragraph-level list attrs to the caret paragraph or selected range.
   When not editing, callers should update the shape content directly."
  [attrs use-shape-fn set-shape-text-content-fn]
  (when wasm/context-initialized?
    (let [shape-id (text-editor-get-active-shape-id)]
      (when shape-id
        (let [content (get-cached-content shape-id)]
          (when content
            (let [paragraph-set (first (:children content))
                  paragraphs    (:children paragraph-set)
                  [start end]   (selection-paragraph-range paragraphs)
                  list-style    (some-> (:list-style attrs) d/name)
                  list-indent   (when (contains? attrs :list-indent)
                                  (txt/clamp-list-indent (:list-indent attrs)))
                  list-style-position
                  (when (contains? attrs :list-style-position)
                    (txt/normalize-list-style-position (:list-style-position attrs)))
                  new-paragraphs
                  (mapv (fn [idx para]
                          (if (<= start idx end)
                            (cond-> para
                              (some? list-style)
                              (assoc :list-style list-style)

                              (some? list-indent)
                              (assoc :list-indent list-indent)

                              (some? list-style-position)
                              (assoc :list-style-position list-style-position)

                              (= list-style "none")
                              (assoc :list-indent 0)

                              (and (contains? #{"bullet" "numbered"} list-style)
                                   (not (contains? attrs :list-style-position))
                                   (nil? (:list-style-position para)))
                              (assoc :list-style-position txt/default-list-style-position))
                            para))
                        (range (count paragraphs))
                        paragraphs)
                  new-content (assoc content :children
                                     [(assoc paragraph-set :children new-paragraphs)])]
              (update-cached-content! shape-id new-content)
              (use-shape-fn shape-id)
              (set-shape-text-content-fn shape-id new-content)
              {:shape-id shape-id
               :content  new-content})))))))

(defn try-apply-markdown-list
  "If the current paragraph looks like markdown list syntax (`* `, `- `, `+ `, `1. `),
   convert it into a list item and strip the marker characters.
   Returns {:list-style ... :prefix-len N} so the caller can delete the prefix
   and apply the list style, or nil when no conversion applies."
  []
  (when wasm/context-initialized?
    (let [shape-id  (text-editor-get-active-shape-id)
          selection (text-editor-get-selection)]
      (when (and shape-id selection)
        (let [content (get-cached-content shape-id)
              sel     (normalize-selection selection)
              para-idx (:start-para sel)
              offset   (:start-offset sel)]
          (when (and content
                     (= para-idx (:end-para sel))
                     (= offset (:end-offset sel)))
            (let [para      (-> content :children first :children (nth para-idx nil))
                  full-text (->> (:children para) (map :text) (str/join ""))
                  bullet-match   (re-matches #"^([\*\-\+]) (.*)$" full-text)
                  numbered-match (re-matches #"^(\d+)[\.\)] (.*)$" full-text)
                  prefix-len
                  (cond
                    bullet-match 2
                    numbered-match (+ 2 (count (second numbered-match)))
                    :else nil)]
              (when (and prefix-len (= offset prefix-len))
                {:list-style (if bullet-match "bullet" "numbered")
                 :list-style-position txt/default-list-style-position
                 :prefix-len prefix-len
                 :shape-id shape-id}))))))))

(def ^:private bullet-marker-re
  #"^([ \t]*)(?:[\*\-\+]|[•●○◦▪▫‣⁃])[ \t]+(.*)$")

(def ^:private numbered-marker-re
  #"^([ \t]*)(\d+)[.\)][ \t]+(.*)$")

(defn- leading-whitespace->indent
  "Map leading spaces/tabs to a list indent level (2 spaces or 1 tab ≈ 1 level)."
  [prefix]
  (let [expanded (str/replace (or prefix "") #"\t" "  ")]
    (txt/clamp-list-indent (quot (count expanded) 2))))

(defn- normalize-clipboard-newlines
  [text]
  (-> (or text "")
      (str/replace #"\r\n" "\n")
      (str/replace #"\r" "\n")))

(defn parse-plain-text-clipboard
  "Parse pasted plain text into paragraph descriptors.

  Recognizes markdown-style and unicode list markers, including nested
  indentation via leading spaces/tabs. Each item is
  {:text :list-style :list-indent}."
  [text]
  (let [lines (-> text normalize-clipboard-newlines (str/split #"\n"))]
    (mapv (fn [line]
            (let [bullet   (re-matches bullet-marker-re line)
                  numbered (re-matches numbered-marker-re line)]
              (cond
                bullet
                {:text (str/trim (nth bullet 2))
                 :list-style "bullet"
                 :list-indent (leading-whitespace->indent (nth bullet 1))}

                numbered
                {:text (str/trim (nth numbered 3))
                 :list-style "numbered"
                 :list-indent (leading-whitespace->indent (nth numbered 1))}

                :else
                {:text line
                 :list-style "none"
                 :list-indent 0})))
          lines)))

(defn- dom-element-name
  [node]
  (when (and node (= (.-nodeType node) 1))
    (str/lower (.-tagName node))))

(defn- node-text-excluding-lists
  [node]
  (->> (array-seq (.-childNodes node))
       (map (fn [child]
              (let [name (dom-element-name child)]
                (cond
                  (#{"ul" "ol"} name) ""
                  (= (.-nodeType child) 3) (or (.-textContent child) "")
                  (= (.-nodeType child) 1) (node-text-excluding-lists child)
                  :else ""))))
       (str/join "")))

(declare parse-html-list-node)

(defn- parse-html-list-item
  [li-node indent list-style]
  (let [text (str/trim (node-text-excluding-lists li-node))
        nested (->> (array-seq (.-childNodes li-node))
                    (mapcat (fn [child]
                              (when (#{"ul" "ol"} (dom-element-name child))
                                (parse-html-list-node child (inc indent)))))
                    vec)]
    (into [{:text text
            :list-style list-style
            :list-indent (txt/clamp-list-indent indent)}]
          nested)))

(defn- parse-html-list-node
  [list-node indent]
  (let [list-style (if (= (dom-element-name list-node) "ol") "numbered" "bullet")]
    (->> (array-seq (.-childNodes list-node))
         (mapcat (fn [child]
                   (let [name (dom-element-name child)]
                     (cond
                       (= name "li")
                       (parse-html-list-item child indent list-style)

                       ;; Google Docs emits nested lists as siblings of <li>
                       ;; (ul > li + ul) instead of nesting them inside the
                       ;; parent <li>. Keep those nested items.
                       (#{"ul" "ol"} name)
                       (parse-html-list-node child (inc indent))

                       :else nil))))
         vec)))

(defn- parse-html-block-node
  [node]
  (let [name (dom-element-name node)]
    (cond
      (#{"ul" "ol"} name)
      (parse-html-list-node node 0)

      (#{"li"} name)
      (parse-html-list-item node 0 "bullet")

      (#{"p" "div" "h1" "h2" "h3" "h4" "h5" "h6" "blockquote" "pre" "section" "article" "td" "th"} name)
      (let [child-lists (->> (array-seq (.-childNodes node))
                             (filter #(#{"ul" "ol"} (dom-element-name %)))
                             vec)]
        (if (seq child-lists)
          (->> (array-seq (.-childNodes node))
               (mapcat (fn [child]
                         (let [cname (dom-element-name child)]
                           (cond
                             (#{"ul" "ol"} cname) (parse-html-list-node child 0)
                             (= (.-nodeType child) 1) (parse-html-block-node child)
                             :else nil))))
               vec)
          (let [text (str/trim (node-text-excluding-lists node))]
            (when (seq text)
              [{:text text :list-style "none" :list-indent 0}]))))

      (= name "br")
      [{:text "" :list-style "none" :list-indent 0}]

      (some? name)
      (->> (array-seq (.-childNodes node))
           (mapcat parse-html-block-node)
           vec)

      :else
      nil)))

(defn parse-html-clipboard
  "Parse clipboard HTML into paragraph descriptors when it contains lists.

  Returns a vector of {:text :list-style :list-indent}, or nil when the HTML
  has no `<ul>`/`<ol>` structure worth preserving."
  [html]
  (when (and (string? html)
             (re-find #"(?i)<(ul|ol)\b" html))
    (let [doc  (.parseFromString (js/DOMParser.) html "text/html")
          body (.-body doc)
          items (->> (array-seq (.-childNodes body))
                     (mapcat parse-html-block-node)
                     vec)]
      (when (seq items)
        items))))

(defn- clipboard-lines-have-lists?
  [lines]
  (boolean (some #(contains? #{"bullet" "numbered"} (:list-style %)) lines)))

(defn- count-list-items
  [lines]
  (count (filter #(contains? #{"bullet" "numbered"} (:list-style %)) lines)))

(defn- choose-clipboard-lines
  "Prefer HTML lists when present, but fall back to plain text when it
  preserves more list items (common with Google Docs nested-list quirks)."
  [html-lines plain-lines]
  (let [html-ok?  (clipboard-lines-have-lists? html-lines)
        plain-ok? (clipboard-lines-have-lists? plain-lines)]
    (cond
      (and html-ok? plain-ok?)
      (if (>= (count-list-items html-lines) (count-list-items plain-lines))
        html-lines
        plain-lines)

      html-ok? html-lines
      plain-ok? plain-lines
      :else plain-lines)))

(defn- apply-paragraph-list-attrs
  "Set list attrs on paragraphs [start-para, start-para+n) from line descriptors."
  [content start-para line-attrs]
  (let [para-set    (first (:children content))
        paragraphs  (:children para-set)
        end-para    (+ start-para (count line-attrs))
        new-paras
        (mapv (fn [idx para]
                (if (and (>= idx start-para) (< idx end-para))
                  (let [attrs  (nth line-attrs (- idx start-para))
                        style  (or (:list-style attrs) "none")
                        indent (txt/clamp-list-indent (or (:list-indent attrs) 0))]
                    (cond-> para
                      true (assoc :list-style style)
                      true (assoc :list-indent (if (= style "none") 0 indent))
                      (contains? #{"bullet" "numbered"} style)
                      (assoc :list-style-position
                             (or (:list-style-position para)
                                 txt/default-list-style-position))
                      (= style "none")
                      (assoc :list-indent 0)))
                  para))
              (range (count paragraphs))
              paragraphs)]
    (assoc content :children [(assoc para-set :children new-paras)])))

(defn paste-clipboard-text
  "Insert clipboard text into the active WASM text editor.

  When the clipboard contains markdown-style or HTML lists, markers are
  stripped and paragraph list attrs are applied. Returns
  {:shape-id :content} after sync (and optional list-attr application)."
  [plain html use-shape-fn set-shape-text-content-fn]
  (when (and wasm/context-initialized?
             (text-editor-has-focus?))
    (let [shape-id    (text-editor-get-active-shape-id)
          selection   (text-editor-get-selection)
          start-para  (if selection
                        (:start-para (normalize-selection selection))
                        0)
          html-lines  (parse-html-clipboard html)
          plain-lines (when (and (string? plain) (seq plain))
                        (parse-plain-text-clipboard plain))
          lines       (choose-clipboard-lines html-lines plain-lines)
          has-lists?  (clipboard-lines-have-lists? lines)
          insert-text (cond
                        has-lists?
                        (->> lines (map :text) (str/join "\n"))

                        (and (string? plain) (seq plain))
                        (normalize-clipboard-newlines plain)

                        :else nil)]
      (when (and shape-id (string? insert-text) (seq insert-text))
        (text-editor-insert-text insert-text)
        (let [synced (text-editor-sync-content)]
          (if (and synced has-lists?)
            (let [new-content (apply-paragraph-list-attrs
                               (:content synced)
                               start-para
                               lines)]
              (update-cached-content! shape-id new-content)
              (use-shape-fn shape-id)
              (set-shape-text-content-fn shape-id new-content)
              {:shape-id shape-id
               :content  new-content})
            synced))))))
