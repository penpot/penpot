;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api.texts
  (:require
   [app.common.data :as d]
   [app.common.types.fills.impl :as types.fills.impl]
   [app.common.uuid :as uuid]
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.wasm :as wasm]))

(def ^:const PARAGRAPH-ATTR-U8-SIZE 12)
(def ^:const SPAN-ATTR-U8-SIZE 64)
(def ^:const MAX-TEXT-FILLS types.fills.impl/MAX-FILLS)

(defn- encode-text
  "Into an UTF8 buffer. Returns an ArrayBuffer instance"
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
        line-height     (f/serialize-line-height (get paragraph :line-height))
        letter-spacing  (f/serialize-letter-spacing (get paragraph :letter-spacing))]

    (-> offset
        (mem/write-u8 dview text-align)
        (mem/write-u8 dview text-direction)
        (mem/write-u8 dview text-decoration)
        (mem/write-u8 dview text-transform)

        (mem/write-f32 dview line-height)
        (mem/write-f32 dview letter-spacing)

        (mem/assert-written offset PARAGRAPH-ATTR-U8-SIZE))))

(defn- write-spans
  [offset dview spans paragraph]
  (let [paragraph-font-size (get paragraph :font-size)
        paragraph-font-weight (-> paragraph :font-weight f/serialize-font-weight)
        paragraph-line-height (f/serialize-line-height (get paragraph :line-height))]
    (reduce (fn [offset span]
              (let [font-style  (sr/translate-font-style (get span :font-style "normal"))
                    font-size   (get span :font-size paragraph-font-size)
                    font-size   (f/serialize-font-size font-size)

                    line-height     (f/serialize-line-height (get span :line-height) paragraph-line-height)
                    letter-spacing  (f/serialize-letter-spacing (get paragraph :letter-spacing))

                    font-weight (get span :font-weight paragraph-font-weight)
                    font-weight (f/serialize-font-weight font-weight)

                    font-id     (f/normalize-font-id (get span :font-id "sourcesanspro"))
                    font-family (hash (get span :font-family "sourcesanspro"))

                    text-buffer (encode-text (get span :text ""))
                    text-length (mem/size text-buffer)
                    fills       (take MAX-TEXT-FILLS (get span :fills []))

                    font-variant-id
                    (get span :font-variant-id)

                    font-variant-id
                    (if (uuid? font-variant-id)
                      font-variant-id
                      uuid/zero)

                    text-decoration
                    (or (sr/translate-text-decoration (:text-decoration span))
                        (sr/translate-text-decoration (:text-decoration paragraph))
                        (sr/translate-text-decoration "none"))

                    text-transform
                    (or (sr/translate-text-transform (:text-transform span))
                        (sr/translate-text-transform (:text-transform paragraph))
                        (sr/translate-text-transform "none"))

                    text-direction
                    (or (sr/translate-text-direction (:text-direction span))
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

(defn write-shape-text
  ;; buffer has the following format:
  ;; [<num-spans> <paragraph_attributes> <spans_attributes> <text>]
  [spans paragraph text]
  (let [num-spans    (count spans)
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
        (write-spans dview spans paragraph)
        (mem/write-buffer heapu8 text-buffer))

    (h/call wasm/internal-module "_set_shape_text_content")))

(def ^:private emoji-pattern
  #"(?:\uD83C[\uDDE6-\uDDFF]\uD83C[\uDDE6-\uDDFF])|(?:\uD83C[\uDF00-\uDFFF]|\uD83D[\uDC00-\uDEFF])|(?:\uD83E[\uDD00-\uDDFF])|(?:\uD83D[\uDE80-\uDEFF]|\uD83E[\uDC00-\uDCFF])|(?:\uD83E[\uDE70-\uDEFF])|[\u2600-\u26FF\u2700-\u27BF\u2300-\u23FF\u2B00-\u2BFF]")

(def ^:private unicode-ranges
  {:japanese    #"[\u3040-\u30FF\u31F0-\u31FF\uFF66-\uFF9F]"
   :chinese     #"[\u4E00-\u9FFF\u3400-\u4DBF]"
   :korean      #"[\uAC00-\uD7AF]"
   :arabic      #"[\u0600-\u06FF\u0750-\u077F\u0870-\u089F\u08A0-\u08FF]"
   :cyrillic    #"[\u0400-\u04FF\u0500-\u052F\u2DE0-\u2DFF\uA640-\uA69F]"
   :greek       #"[\u0370-\u03FF\u1F00-\u1FFF]"
   :hebrew      #"[\u0590-\u05FF\uFB1D-\uFB4F]"
   :thai        #"[\u0E00-\u0E7F]"
   :devanagari  #"[\u0900-\u097F\uA8E0-\uA8FF]"
   :tamil       #"[\u0B80-\u0BFF]"
   :latin-ext   #"[\u0100-\u017F\u0180-\u024F]"
   :vietnamese  #"[\u1EA0-\u1EF9]"
   :armenian    #"[\u0530-\u058F\uFB13-\uFB17]"
   :bengali     #"[\u0980-\u09FF]"
   :cherokee    #"[\u13A0-\u13FF]"
   :ethiopic    #"[\u1200-\u137F]"
   :georgian    #"[\u10A0-\u10FF]"
   :gujarati    #"[\u0A80-\u0AFF]"
   :gurmukhi    #"[\u0A00-\u0A7F]"
   :khmer       #"[\u1780-\u17FF\u19E0-\u19FF]"
   :lao         #"[\u0E80-\u0EFF]"
   :malayalam   #"[\u0D00-\u0D7F]"
   :myanmar     #"[\u1000-\u109F\uAA60-\uAA7F]"
   :sinhala     #"[\u0D80-\u0DFF]"
   :telugu      #"[\u0C00-\u0C7F]"
   :tibetan     #"[\u0F00-\u0FFF]"
   :javanese    #"[\uA980-\uA9DF]"
   :kannada     #"[\u0C80-\u0CFF]"
   :oriya       #"[\u0B00-\u0B7F]"
   :mongolian   #"[\u1800-\u18AF]"
   :syriac      #"[\u0700-\u074F]"
   :tifinagh    #"[\u2D30-\u2D7F]"
   :coptic      #"[\u2C80-\u2CFF]"
   :ol-chiki    #"[\u1C50-\u1C7F]"
   :vai         #"[\uA500-\uA63F]"
   :shavian     #"\uD801[\uDC50-\uDC7F]"
   :osmanya     #"\uD801[\uDC80-\uDCAF]"
   :runic       #"[\u16A0-\u16FF]"
   :old-italic  #"\uD800[\uDF00-\uDF2F]"
   :brahmi      #"\uD804[\uDC00-\uDC7F]"
   :modi        #"\uD805[\uDE00-\uDE5F]"
   :sora-sompeng #"\uD804[\uDCD0-\uDCFF]"
   :bamum       #"[\uA6A0-\uA6FF]"
   :meroitic    #"\uD802[\uDD80-\uDD9F]"
   ;; Arrows, Mathematical Operators, Misc Technical, Geometric Shapes, Misc Symbols, Dingbats, Supplemental Arrows, etc.
   :symbols     #"[\u2190-\u21FF\u2200-\u22FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u26FF\u2700-\u27BF\u2B00-\u2BFF]"
  ;; Additional arrows, math, technical, geometric, and symbol blocks
   :symbols-2     #"[\u2190-\u21FF\u2200-\u22FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u26FF\u2700-\u27BF\u2B00-\u2BFF]"
   :music     #"[\u2669-\u267B]|\uD834[\uDD00-\uDD1F]"})

(defn contains-emoji? [text]
  (let [result (re-find emoji-pattern text)]
    (boolean result)))

(defn collect-used-languages
  [used text]
  (reduce-kv (fn [result lang pattern]
               (cond
                 ;; Skip regex operation if we already know that
                 ;; langage is present
                 (contains? result lang)
                 result

                 (re-find pattern text)
                 (conj result lang)

                 :else
                 result))
             used
             unicode-ranges))

