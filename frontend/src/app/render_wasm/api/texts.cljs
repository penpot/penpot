;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api.texts
  (:require
   [app.common.data.macros :as dm]
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.mem :as mem]
   [app.render-wasm.serializers :as sr]
   [app.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.serializers.fills :as sr-fills]
   [app.render-wasm.wasm :as wasm]))

(defn utf8->buffer [text]
  (let [encoder (js/TextEncoder.)]
    (.encode encoder text)))

(defn set-text-leaf-fills
  [fills current-offset dview]
  (reduce (fn [offset fill]
            (let [opacity  (or (:fill-opacity fill) 1.0)
                  color    (:fill-color fill)
                  gradient (:fill-color-gradient fill)
                  image    (:fill-image fill)]
              (cond
                (some? color)
                (sr-fills/write-solid-fill! offset dview (sr-clr/hex->u32argb color opacity))

                (some? gradient)
                (sr-fills/write-gradient-fill! offset dview gradient opacity)

                (some? image)
                (sr-fills/write-image-fill! offset dview
                                            (dm/get-prop image :id)
                                            opacity
                                            (dm/get-prop image :width)
                                            (dm/get-prop image :height)))

              (+ offset sr-fills/FILL-BYTE-SIZE)))
          current-offset
          fills))

(defn total-fills-count
  [leaves]
  (reduce #(+ %1 (count (:fills %2))) 0 leaves))

(defn write-shape-text
  ;; buffer has the following format:
  ;; [<num-leaves> <paragraph_attributes> <leaves_attributes> <text>]
  [leaves paragraph text]
  (let [le? true
        num-leaves (count leaves)
        paragraph-attr-size 48
        total-fills (total-fills-count leaves)
        total-fills-size (* sr-fills/FILL-BYTE-SIZE total-fills)
        leaf-attr-size 56
        metadata-size (+ paragraph-attr-size (* num-leaves leaf-attr-size) total-fills-size)
        text-buffer (utf8->buffer text)
        text-size (.-byteLength text-buffer)
        buffer (js/ArrayBuffer. (+ metadata-size text-size))
        dview (js/DataView. buffer)]

    (.setUint32 dview 0 num-leaves le?)

    ;; Serialize paragraph attributes
    (let [text-align (sr/serialize-text-align (:text-align paragraph))
          text-direction (sr/serialize-text-direction (:text-direction paragraph))
          text-decoration (sr/serialize-text-decoration (:text-decoration paragraph))
          text-transform (sr/serialize-text-transform (:text-transform paragraph))
          line-height (:line-height paragraph)
          letter-spacing (:letter-spacing paragraph)
          typography-ref-file (sr/serialize-uuid (:typography-ref-file paragraph))
          typography-ref-id (sr/serialize-uuid (:typography-ref-id paragraph))]

      (.setUint8 dview 4 text-align le?)
      (.setUint8 dview 5 text-direction le?)
      (.setUint8 dview 6 text-decoration le?)
      (.setUint8 dview 7 text-transform le?)

      (.setFloat32 dview 8 line-height le?)
      (.setFloat32 dview 12 letter-spacing le?)

      (.setUint32 dview 16 (aget typography-ref-file 0) le?)
      (.setUint32 dview 20 (aget typography-ref-file 1) le?)
      (.setUint32 dview 24 (aget typography-ref-file 2) le?)
      (.setInt32 dview 28 (aget typography-ref-file 3) le?)

      (.setUint32 dview 32 (aget typography-ref-id 0) le?)
      (.setUint32 dview 36 (aget typography-ref-id 1) le?)
      (.setUint32 dview 40 (aget typography-ref-id 2) le?)
      (.setInt32 dview 44 (aget typography-ref-id 3) le?))

    ;; Serialize leaves attributes
    (loop [index 0 offset paragraph-attr-size]
      (when (< index num-leaves)
        (let [leaf (nth leaves index)
              font-style (f/serialize-font-style (:font-style leaf))
              font-size (:font-size leaf)
              font-weight (:font-weight leaf)
              font-id (f/serialize-font-id (:font-id leaf))
              font-family (hash (:font-family leaf))
              font-variant-id (sr/serialize-uuid (:font-variant-id leaf))
              leaf-text-decoration (or (sr/serialize-text-decoration (:text-decoration leaf)) (sr/serialize-text-decoration (:text-decoration paragraph)))
              leaf-text-transform (or (sr/serialize-text-transform (:text-transform leaf)) (sr/serialize-text-transform (:text-transform paragraph)))
              text-buffer (utf8->buffer (:text leaf))
              text-length (.-byteLength text-buffer)
              fills (:fills leaf)
              total-fills (count fills)]

          (.setUint8 dview offset font-style le?)
          (.setUint8 dview (+ offset 1) leaf-text-decoration le?)
          (.setUint8 dview (+ offset 2) leaf-text-transform le?)

          (.setFloat32 dview (+ offset 4) font-size le?)
          (.setUint32 dview (+ offset 8) font-weight le?)
          (.setUint32 dview (+ offset 12) (aget font-id 0) le?)
          (.setUint32 dview (+ offset 16) (aget font-id 1) le?)
          (.setUint32 dview (+ offset 20) (aget font-id 2) le?)
          (.setInt32 dview (+ offset 24) (aget font-id 3) le?)

          (.setInt32 dview (+ offset 28) font-family le?)

          (.setUint32 dview (+ offset 32) (aget font-variant-id 0) le?)
          (.setUint32 dview (+ offset 36) (aget font-variant-id 1) le?)
          (.setUint32 dview (+ offset 40) (aget font-variant-id 2) le?)
          (.setInt32 dview (+ offset 44) (aget font-variant-id 3) le?)

          (.setInt32 dview (+ offset 48) text-length le?)
          (.setInt32 dview (+ offset 52) total-fills le?)

          (let [new-offset (set-text-leaf-fills fills (+ offset leaf-attr-size) dview)]
            (recur (inc index) new-offset)))))

    ;; Add text content to buffer
    (let [text-offset metadata-size
          buffer-u8 (js/Uint8Array. buffer)]
      (.set buffer-u8 (js/Uint8Array. text-buffer) text-offset))

    ;; Allocate memory and set buffer
    (let [total-size (.-byteLength buffer)
          metadata-offset (mem/alloc-bytes total-size)
          heap (mem/get-heap-u8)]
      (.set heap (js/Uint8Array. buffer) metadata-offset)))

  (h/call wasm/internal-module "_set_shape_text_content"))

(def ^:private emoji-pattern #"[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF]")

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
   :meroitic    #"[\u10980-\u1099F]"})


(defn contains-emoji? [text]
  (boolean (some #(re-find emoji-pattern %) (seq text))))

(defn get-languages [text]
  (reduce-kv (fn [result lang pattern]
               (if (re-find pattern text)
                 (conj result lang)
                 result))
             #{}
             unicode-ranges))
