;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.text-editor
  "Text editor WASM bindings"
  (:require
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.uuid :as uuid]
   [app.main.fonts :as main-fonts]
   [app.render-wasm.api.fonts :as fonts]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.wasm :as wasm]))

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
      0
      (let [rgba (aget heap-u32 (+ fill-u32-offset 1))]
        {:fill-color (rgba->fill-color rgba)
         :fill-opacity (rgba->opacity rgba)})

      1
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

      2
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

      3
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
              font-family-state (aget heap-u32 (+ u32-offset 5))
              font-size-state (aget heap-u32 (+ u32-offset 6))
              font-weight-state (aget heap-u32 (+ u32-offset 7))
              font-variant-id-state (aget heap-u32 (+ u32-offset 8))
              line-height-state (aget heap-u32 (+ u32-offset 9))
              letter-spacing-state (aget heap-u32 (+ u32-offset 10))
              num-fills (aget heap-u32 (+ u32-offset 11))

              text-align-value (aget heap-u32 (+ u32-offset 12))
              text-direction-value (aget heap-u32 (+ u32-offset 13))
              text-decoration-value (aget heap-u32 (+ u32-offset 14))
              text-transform-value (aget heap-u32 (+ u32-offset 15))
              font-family-id-a (aget heap-u32 (+ u32-offset 16))
              font-family-id-b (aget heap-u32 (+ u32-offset 17))
              font-family-id-c (aget heap-u32 (+ u32-offset 18))
              font-family-id-d (aget heap-u32 (+ u32-offset 19))
              font-family-id-value (uuid/from-unsigned-parts font-family-id-a font-family-id-b font-family-id-c font-family-id-d)
              font-family-style-value (aget heap-u32 (+ u32-offset 20))
              _font-family-weight-value (aget heap-u32 (+ u32-offset 21))
              font-size-value (aget heap-f32 (+ u32-offset 22))
              font-weight-value (aget heap-i32 (+ u32-offset 23))
              line-height-value (aget heap-f32 (+ u32-offset 28))
              letter-spacing-value (aget heap-f32 (+ u32-offset 29))
              font-id (fonts/uuid->font-id font-family-id-value)
              font-style-value (text-editor-translate-font-style (text-editor-get-style-property font-family-state font-family-style-value))
              font-variant-id-computed (text-editor-compute-font-variant-id font-id font-weight-value font-style-value)

              fills (->> (range num-fills)
                         (map (fn [idx]
                                (read-fill-from-heap
                                 heap-u8 heap-u32 heap-i32 heap-f32
                                 (+ ptr
                                    (* 28 4)
                                    (* idx types.fills.impl/FILL-U8-SIZE)))))
                         (filter some?)
                         (into []))

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
                      :font-family (text-editor-get-style-property font-family-state font-id)
                      :font-id (text-editor-get-style-property font-family-state font-id)
                      :font-variant-id (text-editor-get-style-property font-variant-id-state font-variant-id-computed)
                      :typography-ref-file nil
                      :typography-ref-id nil
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
              content   (get @shape-text-contents shape-id)]
          (when content
            (let [merged (merge-exported-texts-into-content content texts-clj)]
              (swap! shape-text-contents assoc shape-id merged)
              {:shape-id shape-id
               :content  merged})))))))

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

(defn apply-style-to-selection
  [attrs use-shape-fn set-shape-text-content-fn]
  (when wasm/context-initialized?
    (let [shape-id (text-editor-get-active-shape-id)
          sel      (text-editor-get-selection)]
      (when (and shape-id sel)
        (let [content (get @shape-text-contents shape-id)]
          (when content
            (let [{:keys [start-para start-offset end-para end-offset]}
                  (normalize-selection sel)
                  collapsed? (and (= start-para end-para) (= start-offset end-offset))
                  para-set   (first (:children content))
                  paras      (:children para-set)
                  new-paras
                  (when (not collapsed?)
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
                          (range (count paras))
                          paras))
                  new-content (when new-paras
                                (assoc content :children
                                       [(assoc para-set :children new-paras)]))]
              (when new-content
                (swap! shape-text-contents assoc shape-id new-content)
                (use-shape-fn shape-id)
                (set-shape-text-content-fn shape-id new-content)
                {:shape-id shape-id
                 :content  new-content}))))))))
