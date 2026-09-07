;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.wasm.text
  "Browser-free text-content serialization for the headless exporter. Only the
  paragraph walk is local: the binary layout and the font-id -> uuid mapping
  both come from `app.common.render-wasm.text-content`."
  (:require
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.text-content :as tc]
   [app.common.render-wasm.wasm :as wasm]))

(defn set-shape-text!
  "Serializes a text shape's content into the current WASM shape. Mirrors the
  editor's sequence: clear -> vertical-align -> append each paragraph -> layout.
  Byte writing and font resolution are the shared
  `text-content/write-shape-text!` defaults; the exporter has no fonts DB, so
  it injects no variant normalization."
  [content]
  (when content
    (h/call wasm/internal-module "_clear_shape_text")
    (h/call wasm/internal-module "_set_shape_vertical_align"
            (sr/translate-vertical-align (get content :vertical-align)))
    (let [paragraph-set (first (get content :children))
          paragraphs    (get paragraph-set :children)]
      (doseq [paragraph paragraphs]
        (let [spans (get paragraph :children)]
          (when (seq spans)
            (let [text (apply str (map :text spans))]
              (tc/write-shape-text! spans paragraph text {}))))))
    (h/call wasm/internal-module "_update_shape_text_layout")))
