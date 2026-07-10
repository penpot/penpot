;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.text
  "Browser-free text-content serialization for the headless exporter.

  The binary text layout is shared with the workspace via
  `app.render-wasm.text-content`; only *font-id resolution* is local here,
  because the exporter has no fonts DB: custom fonts keep their uuid
  (`custom-<uuid>`), google fonts (`gfont-<slug>`) map via the compile-time
  `app.wasm.gfonts` catalog, and builtin falls back to the default (uuid/zero)."
  (:require
   [app.common.uuid :as uuid]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.text-content :as tc]
   [app.render-wasm.wasm :as wasm]
   [app.wasm.gfonts :as gfonts]
   [cuerdas.core :as str]))

(defn- normalize-font-id
  "Maps a content font-id to its wasm uuid. The provisioning side keys on the
  same uuid."
  [font-id]
  (try
    (cond
      (str/starts-with? font-id "gfont-")
      (or (gfonts/gfont-id->uuid font-id) uuid/zero)

      (str/includes? font-id "-")
      (let [no-prefix (subs font-id (inc (str/index-of font-id "-")))]
        (if (str/blank? no-prefix) uuid/zero (uuid/parse no-prefix)))

      :else uuid/zero)
    (catch :default _ uuid/zero)))

(defn set-shape-text!
  "Serializes a text shape's content into the current WASM shape. Mirrors the
  editor's sequence: clear -> vertical-align -> append each paragraph -> layout.
  Byte writing is the shared `text-content/write-shape-text!`; only font-id
  resolution is injected."
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
              (tc/write-shape-text! spans paragraph text
                                    {:normalize-font-id normalize-font-id}))))))
    (h/call wasm/internal-module "_update_shape_text_layout")))
