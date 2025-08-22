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

(def ^:const PARAGRAPH-ATTR-U8-SIZE 44)
(def ^:const LEAF-ATTR-U8-SIZE 60)

(defn- encode-text
  "Into an UTF8 buffer. Returns an ArrayBuffer instance"
  [text]
  (let [encoder (js/TextEncoder.)]
    (.encode encoder text)))

(defn- write-leaf-fills
  [offset dview fills]
  (reduce (fn [offset fill]
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
          fills))

(defn- get-total-fills
  [leaves]
  (reduce #(+ %1 (count (:fills %2))) 0 leaves))

(defn- write-paragraph
  [offset dview paragraph]
  (let [text-align      (sr/translate-text-align (get paragraph :text-align))
        text-direction  (sr/translate-text-direction (get paragraph :text-direction))
        text-decoration (sr/translate-text-decoration (get paragraph :text-decoration))
        text-transform  (sr/translate-text-transform (get paragraph :text-transform))
        line-height     (get paragraph :line-height)
        letter-spacing  (get paragraph :letter-spacing)

        typography-ref-file (get paragraph :typography-ref-file)
        typography-ref-id   (get paragraph :typography-ref-id)]

    (-> offset
        (mem/write-u8 dview text-align)
        (mem/write-u8 dview text-direction)
        (mem/write-u8 dview text-decoration)
        (mem/write-u8 dview text-transform)

        (mem/write-f32 dview line-height)
        (mem/write-f32 dview letter-spacing)

        (mem/write-uuid dview (d/nilv typography-ref-file uuid/zero))
        (mem/write-uuid dview (d/nilv typography-ref-id uuid/zero))
        (mem/assert-written offset PARAGRAPH-ATTR-U8-SIZE))))

(defn- write-leaves
  [offset dview leaves paragraph]
  (reduce (fn [offset leaf]
            (let [font-style  (sr/translate-font-style (get leaf :font-style))
                  font-size   (get leaf :font-size)
                  letter-spacing (get leaf :letter-spacing)
                  font-weight (get leaf :font-weight)
                  font-id     (f/normalize-font-id (get leaf :font-id))
                  font-family (hash (get leaf :font-family))

                  text-buffer (encode-text (get leaf :text))
                  text-length (mem/size text-buffer)
                  fills       (get leaf :fills)
                  total-fills (count fills)

                  font-variant-id
                  (get leaf :font-variant-id)

                  font-variant-id
                  (if (uuid? font-variant-id)
                    font-variant-id
                    uuid/zero)

                  text-decoration
                  (or (sr/translate-text-decoration (:text-decoration leaf))
                      (sr/translate-text-decoration (:text-decoration paragraph))
                      (sr/translate-text-decoration "none"))

                  text-transform
                  (or (sr/translate-text-transform (:text-transform leaf))
                      (sr/translate-text-transform (:text-transform paragraph))
                      (sr/translate-text-transform "none"))

                  text-direction
                  (or (sr/translate-text-direction (:text-direction leaf))
                      (sr/translate-text-direction (:text-direction paragraph))
                      (sr/translate-text-direction "ltr"))]

              (-> offset
                  (mem/write-u8 dview font-style)
                  (mem/write-u8 dview text-decoration)
                  (mem/write-u8 dview text-transform)
                  (mem/write-u8 dview text-direction)

                  (mem/write-f32 dview font-size)
                  (mem/write-f32 dview letter-spacing)
                  (mem/write-u32 dview font-weight)

                  (mem/write-uuid dview font-id)
                  (mem/write-i32 dview font-family)
                  (mem/write-uuid dview (d/nilv font-variant-id uuid/zero))

                  (mem/write-i32 dview text-length)
                  (mem/write-i32 dview total-fills)
                  (mem/assert-written offset LEAF-ATTR-U8-SIZE)

                  (write-leaf-fills dview fills))))
          offset
          leaves))

(defn write-shape-text
  ;; buffer has the following format:
  ;; [<num-leaves> <paragraph_attributes> <leaves_attributes> <text>]
  [leaves paragraph text]
  (let [num-leaves    (count leaves)
        fills-size    (* types.fills.impl/FILL-U8-SIZE
                         (get-total-fills leaves))
        metadata-size (+ PARAGRAPH-ATTR-U8-SIZE
                         (* num-leaves LEAF-ATTR-U8-SIZE)
                         fills-size)

        text-buffer   (encode-text text)
        text-size     (mem/size text-buffer)

        total-size    (+ 4 metadata-size text-size)
        heapu8        (mem/get-heap-u8)
        dview         (mem/get-data-view)
        offset        (mem/alloc total-size)]

    (-> offset
        (mem/write-u32 dview num-leaves)
        (write-paragraph dview paragraph)
        (write-leaves dview leaves paragraph)
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
   :shavian     #"[\u10450-\u1047F]"
   :osmanya     #"[\u10480-\u104AF]"
   :runic       #"[\u16A0-\u16FF]"
   :old-italic  #"[\u10300-\u1032F]"
   :brahmi      #"[\u11000-\u1107F]"
   :modi        #"[\u11600-\u1165F]"
   :sora-sompeng #"[\u110D0-\u110FF]"
   :bamum       #"[\uA6A0-\uA6FF]"
   :meroitic    #"[\u10980-\u1099F]"
   ;; Arrows, Mathematical Operators, Misc Technical, Geometric Shapes, Misc Symbols, Dingbats, Supplemental Arrows, etc.
   :symbols     #"[\u2190-\u21FF\u2200-\u22FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u26FF\u2700-\u27BF\u2B00-\u2BFF]"
  ;; Additional arrows, math, technical, geometric, and symbol blocks
   :symbols-2     #"[\u2190-\u21FF\u2200-\u22FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u26FF\u2700-\u27BF\u2B00-\u2BFF]"
   :music     #"[\u2669-\u267B\u1D100-\u1D1FF]"})

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

