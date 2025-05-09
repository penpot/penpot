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
              text-buffer (utf8->buffer (:text leaf))
              text-length (.-byteLength text-buffer)
              fills (:fills leaf)
              total-fills (count fills)]

          (.setUint8 dview offset font-style le?)
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

(def emoji-pattern #"[\uD83C-\uDBFF][\uDC00-\uDFFF]")

(defn contains-emoji? [s]
  (boolean (re-find emoji-pattern s)))
