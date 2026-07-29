;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.text-content
  "Single source of truth for writing a text shape's content into the WASM design
  state. The binary layout ([num-spans][paragraph attrs][span attrs][text]) is
  identical for the workspace and the headless exporter — only *font resolution*
  differs (the workspace uses the loaded fonts DB; the exporter uses its gfonts
  catalog + custom variants). So the byte-writing lives here and font resolution
  is injected via the `opts` map passed to `write-shape-text!`.

  Fully portable (no store/DOM/React), so it runs under Node too."
  (:require
   [app.common.data :as d]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.uuid :as uuid]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.wasm :as wasm]
   [cuerdas.core :as str]))

(def ^:const PARAGRAPH-ATTR-U8-SIZE 12)
(def ^:const SPAN-ATTR-U8-SIZE 64)
(def ^:const MAX-TEXT-FILLS types.fills.impl/MAX-FILLS)

(def ^:private default-font-size 14)
(def ^:private default-line-height 1.2)
(def ^:private default-letter-spacing 0.0)

;; --- pure attribute serializers -------------------------------------------

(defn serialize-font-size
  [font-size]
  (cond
    (number? font-size) font-size
    (string? font-size) (or (d/parse-double font-size) default-font-size)
    :else default-font-size))

(defn serialize-font-weight
  [font-weight]
  (if (number? font-weight)
    font-weight
    (let [font-weight-str (str font-weight)]
      (cond
        (re-matches #"\d+" font-weight-str) (js/Number font-weight-str)
        (str/includes? font-weight-str "bold") 700
        (str/includes? font-weight-str "black") 900
        (str/includes? font-weight-str "extrabold") 800
        (str/includes? font-weight-str "extralight") 200
        (str/includes? font-weight-str "light") 300
        (str/includes? font-weight-str "medium") 500
        (str/includes? font-weight-str "semibold") 600
        (str/includes? font-weight-str "thin") 100
        :else 400))))

(defn serialize-line-height
  ([line-height] (serialize-line-height line-height default-line-height))
  ([line-height default-value]
   (cond
     (number? line-height) line-height
     (string? line-height) (or (d/parse-double line-height) default-value)
     :else default-value)))

(defn serialize-letter-spacing
  [letter-spacing]
  (cond
    (number? letter-spacing) letter-spacing
    (string? letter-spacing) (or (d/parse-double letter-spacing) default-letter-spacing)
    :else default-letter-spacing))

;; --- binary writers --------------------------------------------------------

(defn- encode-text
  "Into an UTF8 buffer. Returns an ArrayBuffer instance."
  [text]
  (let [encoder (js/TextEncoder.)]
    (.encode encoder text)))

(defn- write-span-fills
  [offset dview fills]
  (let [new-ofset (reduce (fn [offset fill]
                            (let [opacity  (get fill :fill-opacity 1.0)
                                  color    (get fill :fill-color)
                                  gradient (get fill :fill-color-gradient)
                                  image    (get fill :fill-image)]
                              (cond
                                (some? color)
                                (types.fills.impl/write-solid-fill offset dview opacity color)

                                (some? gradient)
                                (types.fills.impl/write-gradient-fill offset dview opacity gradient)

                                (some? image)
                                (types.fills.impl/write-image-fill offset dview opacity image))))
                          offset
                          fills)
        padding-fills (max 0 (- MAX-TEXT-FILLS (count fills)))]
    (+ new-ofset (* padding-fills types.fills.impl/FILL-U8-SIZE))))

(defn- write-paragraph
  [offset dview paragraph]
  (let [text-align      (sr/translate-text-align (get paragraph :text-align))
        text-direction  (sr/translate-text-direction (get paragraph :text-direction))
        text-decoration (sr/translate-text-decoration (get paragraph :text-decoration))
        text-transform  (sr/translate-text-transform (get paragraph :text-transform))
        line-height     (serialize-line-height (get paragraph :line-height))
        letter-spacing  (serialize-letter-spacing (get paragraph :letter-spacing))]
    (-> offset
        (mem/write-u8 dview text-align)
        (mem/write-u8 dview text-direction)
        (mem/write-u8 dview text-decoration)
        (mem/write-u8 dview text-transform)
        (mem/write-f32 dview line-height)
        (mem/write-f32 dview letter-spacing)
        (mem/assert-written offset PARAGRAPH-ATTR-U8-SIZE))))

(defn- write-spans
  [offset dview spans paragraph normalize-font-id]
  (let [paragraph-font-size   (get paragraph :font-size)
        paragraph-font-weight (-> paragraph :font-weight serialize-font-weight)
        paragraph-line-height (serialize-line-height (get paragraph :line-height))]
    (reduce
     (fn [offset span]
       (let [font-style      (sr/translate-font-style (get span :font-style "normal"))
             font-size       (serialize-font-size (get span :font-size paragraph-font-size))
             line-height     (serialize-line-height (get span :line-height) paragraph-line-height)
             letter-spacing  (serialize-letter-spacing (get span :letter-spacing))
             font-weight     (serialize-font-weight (get span :font-weight paragraph-font-weight))
             font-id         (normalize-font-id (get span :font-id "sourcesanspro"))
             font-family     (hash (get span :font-family "sourcesanspro"))
             text-buffer     (encode-text (get span :text ""))
             text-length     (mem/size text-buffer)
             fills           (take MAX-TEXT-FILLS (get span :fills []))
             font-variant-id (get span :font-variant-id)
             font-variant-id (if (uuid? font-variant-id) font-variant-id uuid/zero)
             text-decoration (or (sr/translate-text-decoration (:text-decoration span))
                                 (sr/translate-text-decoration (:text-decoration paragraph))
                                 (sr/translate-text-decoration "none"))
             text-transform  (or (sr/translate-text-transform (:text-transform span))
                                 (sr/translate-text-transform (:text-transform paragraph))
                                 (sr/translate-text-transform "none"))
             text-direction  (or (sr/translate-text-direction (:text-direction span))
                                 (sr/translate-text-direction (:text-direction paragraph))
                                 (sr/translate-text-direction "ltr"))]
         (-> offset
             (mem/write-u8 dview font-style)
             (mem/write-u8 dview text-decoration)
             (mem/write-u8 dview text-transform)
             (mem/write-u8 dview text-direction)
             (mem/write-f32 dview font-size)
             (mem/write-f32 dview line-height)
             (mem/write-f32 dview letter-spacing)
             (mem/write-u32 dview font-weight)
             (mem/write-uuid dview font-id)
             (mem/write-i32 dview font-family)
             (mem/write-uuid dview (d/nilv font-variant-id uuid/zero))
             (mem/write-i32 dview text-length)
             (mem/write-i32 dview (count fills))
             (mem/assert-written offset SPAN-ATTR-U8-SIZE)
             (write-span-fills dview fills))))
     offset
     spans)))

(defn write-shape-text!
  "Writes one paragraph's spans + text into WASM and appends it to the current
  shape via `_set_shape_text_content`.

  `opts` injects host-specific font resolution:
   - `:normalize-font-id`  (string font-id -> wasm uuid) — required in practice,
   - `:normalize-paragraph`/`:normalize-span` — font-variant normalization from a
     fonts DB (workspace); default to identity (the exporter resolves variants
     differently / not at all)."
  [spans paragraph text {:keys [normalize-font-id normalize-paragraph normalize-span]
                         :or   {normalize-font-id   identity
                                normalize-paragraph identity
                                normalize-span      (fn [span _paragraph] span)}}]
  (let [paragraph     (normalize-paragraph paragraph)
        spans         (map #(normalize-span % paragraph) spans)
        num-spans     (count spans)
        fills-size    (* types.fills.impl/FILL-U8-SIZE MAX-TEXT-FILLS)
        metadata-size (+ PARAGRAPH-ATTR-U8-SIZE
                         (* num-spans (+ SPAN-ATTR-U8-SIZE fills-size)))
        text-buffer   (encode-text text)
        text-size     (mem/size text-buffer)
        total-size    (+ 4 metadata-size text-size)
        heapu8        (mem/get-heap-u8)
        dview         (mem/get-data-view)
        offset        (mem/alloc total-size)]
    (-> offset
        (mem/write-u32 dview num-spans)
        (write-paragraph dview paragraph)
        (write-spans dview spans paragraph normalize-font-id)
        (mem/write-buffer heapu8 text-buffer))
    (h/call wasm/internal-module "_set_shape_text_content")))
