;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.text-editor
  "Text editor WASM bindings"
  (:require
   [app.common.uuid :as uuid]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.wasm :as wasm]))

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

(defn text-editor-insert-text
  [text]
  (when wasm/context-initialized?
    (let [encoder (js/TextEncoder.)
          buf (.encode encoder text)
          heapu8 (mem/get-heap-u8)
          size (mem/size buf)
          offset (mem/alloc size)]
      (mem/write-buffer offset heapu8 buf)
      (h/call wasm/internal-module "_text_editor_insert_text")
      (mem/free))))

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
        (let [json-str (mem/read-null-terminated-string ptr)]
          (mem/free)
          (js/JSON.parse json-str))))))

(defn text-editor-export-selection
  "Export only the currently selected text as plain text from the WASM editor. Requires WASM support (_text_editor_export_selection)."
  []
  (when wasm/context-initialized?
    (let [ptr (h/call wasm/internal-module "_text_editor_export_selection")]
      (when (and ptr (not (zero? ptr)))
        (let [text (mem/read-null-terminated-string ptr)]
          (mem/free)
          text)))))

;; ---------------------------------------------------------------------------
;; Binary layout constants for TextEditorStyles (must match text_editor.rs)
;; ---------------------------------------------------------------------------
(def ^:const STYLES-HEADER-SIZE 120)

;; RAW_FILL_DATA_SIZE = 4 (tag+padding) + RawGradientData (largest variant)
;; RawGradientData = 28 header + 16 stops × 8 bytes = 156
(def ^:const RAW-FILL-DATA-SIZE 160)

;; MultipleState enum values
(def ^:const MULTIPLE-UNDEFINED 0)
(def ^:const MULTIPLE-SINGLE 1)
(def ^:const MULTIPLE-MULTIPLE 2)

(def ^:private vertical-align-map
  {0 :top 1 :center 2 :bottom})

(def ^:private text-align-map
  {0 :left 1 :center 2 :right 3 :justify})

(def ^:private text-direction-map
  {0 :ltr 1 :rtl})

(def ^:private text-decoration-map
  {0 nil 1 :underline 2 :line-through 3 :overline})

(def ^:private text-transform-map
  {0 nil 1 :uppercase 2 :lowercase 3 :capitalize})

(def ^:private font-style-map
  {0 :normal 1 :italic})

(defn- read-multiple
  "Read a Multiple<T> field from a DataView. Returns :mixed when Multiple,
   nil when Undefined, or calls value-fn to read the value when Single."
  [dview state-offset value-fn]
  (let [state (.getUint32 dview state-offset true)]
    (case state
      1 (value-fn)     ;; Single
      2 :mixed         ;; Multiple
      nil)))           ;; Undefined

(defn- u32-argb->hex
  "Convert u32 ARGB to #RRGGBB hex string."
  [argb]
  (let [r (bit-and (unsigned-bit-shift-right argb 16) 0xFF)
        g (bit-and (unsigned-bit-shift-right argb 8) 0xFF)
        b (bit-and argb 0xFF)]
    (str "#"
         (.padStart (.toString r 16) 2 "0")
         (.padStart (.toString g 16) 2 "0")
         (.padStart (.toString b 16) 2 "0"))))

(defn- u32-argb->opacity
  "Extract normalized opacity (0.0-1.0) from u32 ARGB."
  [argb]
  (/ (bit-and (unsigned-bit-shift-right argb 24) 0xFF) 255.0))

(defn- read-gradient-stops
  "Read gradient stops from DataView at the given byte offset."
  [dview base-offset stop-count]
  (let [stops-offset (+ base-offset 28)] ;; stops start at byte 28 within gradient data
    (into []
          (map (fn [i]
                 (let [stop-off (+ stops-offset (* i 8))
                       color (.getUint32 dview stop-off true)
                       offset (.getFloat32 dview (+ stop-off 4) true)]
                   {:color (u32-argb->hex color)
                    :opacity (u32-argb->opacity color)
                    :offset offset})))
          (range stop-count))))

(defn- read-fill-from-dview
  "Read a single RawFillData entry from DataView at the given byte offset.
   Returns a fill map in the standard Penpot text content format."
  [dview fill-offset]
  (let [tag (.getUint8 dview fill-offset)]
    (case tag
      ;; Solid fill
      0 (let [color (.getUint32 dview (+ fill-offset 4) true)]
          {:fill-color (u32-argb->hex color)
           :fill-opacity (u32-argb->opacity color)})

      ;; Linear gradient
      1 (let [base (+ fill-offset 4)
              stop-count (.getUint8 dview (+ base 24))]
          {:fill-color-gradient
           {:type :linear
            :start-x (.getFloat32 dview base true)
            :start-y (.getFloat32 dview (+ base 4) true)
            :end-x (.getFloat32 dview (+ base 8) true)
            :end-y (.getFloat32 dview (+ base 12) true)
            :width (.getFloat32 dview (+ base 20) true)
            :stops (read-gradient-stops dview base stop-count)}
           :fill-opacity (/ (.getUint8 dview (+ base 16)) 255.0)})

      ;; Radial gradient
      2 (let [base (+ fill-offset 4)
              stop-count (.getUint8 dview (+ base 24))]
          {:fill-color-gradient
           {:type :radial
            :start-x (.getFloat32 dview base true)
            :start-y (.getFloat32 dview (+ base 4) true)
            :end-x (.getFloat32 dview (+ base 8) true)
            :end-y (.getFloat32 dview (+ base 12) true)
            :width (.getFloat32 dview (+ base 20) true)
            :stops (read-gradient-stops dview base stop-count)}
           :fill-opacity (/ (.getUint8 dview (+ base 16)) 255.0)})

      ;; Image fill
      3 (let [base (+ fill-offset 4)
              a (.getUint32 dview base true)
              b (.getUint32 dview (+ base 4) true)
              c (.getUint32 dview (+ base 8) true)
              d (.getUint32 dview (+ base 12) true)
              opacity (.getUint8 dview (+ base 16))
              flags (.getUint8 dview (+ base 17))
              width (.getInt32 dview (+ base 20) true)
              height (.getInt32 dview (+ base 24) true)]
          {:fill-image
           {:id (uuid/from-unsigned-parts a b c d)
            :width width
            :height height
            :keep-aspect-ratio (not (zero? (bit-and flags 1)))}
           :fill-opacity (/ opacity 255.0)})

      ;; Unknown tag
      nil)))

(defn text-editor-get-current-styles
  "Read the current text editor styles from WASM. Returns a map with the
   style values for the current selection, or nil if no selection/focus.
   Multiple<T> fields return :mixed when spans have different values."
  []
  (when wasm/context-initialized?
    (let [ptr (h/call wasm/internal-module "_text_editor_get_current_styles")]
      (when (and ptr (not (zero? ptr)))
        (let [heap-u8 (mem/get-heap-u8)
              dview   (js/DataView. (.-buffer heap-u8) (.-byteOffset heap-u8))

              ;; Read fills count first to know total size
              fills-count (.getUint32 dview (+ ptr 116) true)

              ;; Read scalar Multiple<T> fields
              vertical-align (get vertical-align-map
                                  (.getUint32 dview ptr true)
                                  :top)

              text-align
              (read-multiple dview (+ ptr 4)
                             #(get text-align-map (.getUint32 dview (+ ptr 8) true)))

              text-direction
              (read-multiple dview (+ ptr 12)
                             #(get text-direction-map (.getUint32 dview (+ ptr 16) true)))

              text-decoration
              (read-multiple dview (+ ptr 20)
                             #(get text-decoration-map (.getUint32 dview (+ ptr 24) true)))

              text-transform
              (read-multiple dview (+ ptr 28)
                             #(get text-transform-map (.getUint32 dview (+ ptr 32) true)))

              font-size
              (read-multiple dview (+ ptr 36)
                             #(.getFloat32 dview (+ ptr 40) true))

              font-weight
              (read-multiple dview (+ ptr 44)
                             #(.getInt32 dview (+ ptr 48) true))

              line-height
              (read-multiple dview (+ ptr 52)
                             #(.getFloat32 dview (+ ptr 56) true))

              letter-spacing
              (read-multiple dview (+ ptr 60)
                             #(.getFloat32 dview (+ ptr 64) true))

              font-family
              (read-multiple dview (+ ptr 68)
                             (fn []
                               (let [a (.getUint32 dview (+ ptr 72) true)
                                     b (.getUint32 dview (+ ptr 76) true)
                                     c (.getUint32 dview (+ ptr 80) true)
                                     d (.getUint32 dview (+ ptr 84) true)]
                                 {:id (uuid/from-unsigned-parts a b c d)
                                  :style (get font-style-map (.getUint32 dview (+ ptr 88) true))
                                  :weight (.getUint32 dview (+ ptr 92) true)})))

              font-variant-id
              (read-multiple dview (+ ptr 96)
                             (fn []
                               (let [a (.getUint32 dview (+ ptr 100) true)
                                     b (.getUint32 dview (+ ptr 104) true)
                                     c (.getUint32 dview (+ ptr 108) true)
                                     d (.getUint32 dview (+ ptr 112) true)]
                                 (uuid/from-unsigned-parts a b c d))))

              ;; Read fills
              fills
              (into []
                    (keep (fn [i]
                            (read-fill-from-dview dview
                                                  (+ ptr STYLES-HEADER-SIZE
                                                     (* i RAW-FILL-DATA-SIZE)))))
                    (range fills-count))]

          (mem/free)
          {:vertical-align  vertical-align
           :text-align      text-align
           :text-direction  text-direction
           :text-decoration text-decoration
           :text-transform  text-transform
           :font-size       font-size
           :font-weight     font-weight
           :line-height     line-height
           :letter-spacing  letter-spacing
           :font-family     font-family
           :font-variant-id font-variant-id
           :fills           fills})))))

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
