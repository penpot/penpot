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

(defn text-editor-start
  [id]
  (when wasm/context-initialized?
    (let [buffer (uuid/get-u32 id)]
      (h/call wasm/internal-module "_text_editor_start"
              (aget buffer 0)
              (aget buffer 1)
              (aget buffer 2)
              (aget buffer 3)))))

(defn text-editor-set-cursor-from-point
  [x y]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_set_cursor_from_point" x y)))

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

(defn text-editor-delete-backward []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_delete_backward")))

(defn text-editor-delete-forward []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_delete_forward")))

(defn text-editor-insert-paragraph []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_insert_paragraph")))

(defn text-editor-move-cursor
  [direction extend-selection]
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_move_cursor" direction (if extend-selection 1 0))))

(defn text-editor-select-all
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_select_all")))

(defn text-editor-stop
  []
  (when wasm/context-initialized?
    (h/call wasm/internal-module "_text_editor_stop")))

(defn text-editor-is-active?
  []
  (when wasm/context-initialized?
    (not (zero? (h/call wasm/internal-module "_text_editor_is_active")))))

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
    (let [byte-offset (mem/alloc 16)
          u32-offset  (mem/->offset-32 byte-offset)
          heap        (mem/get-heap-u32)
          active?     (h/call wasm/internal-module "_text_editor_get_selection" byte-offset)]
      (try
        (when (= active? 1)
          {:anchor-para   (aget heap u32-offset)
           :anchor-offset (aget heap (+ u32-offset 1))
           :focus-para    (aget heap (+ u32-offset 2))
           :focus-offset  (aget heap (+ u32-offset 3))})
        (finally
          (mem/free))))))

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
  (when (and wasm/context-initialized? (text-editor-is-active?))
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
  (when (and wasm/context-initialized? (text-editor-is-active?))
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
