(ns app.render-wasm.serializers.color
  (:require
   [app.common.math :as mth]))

(defn hex->u32argb
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and returns its 32-bit argb representation"
  [hex opacity]
  (let [rgb (js/parseInt (subs hex 1) 16)
        a (mth/floor (* (or opacity 1) 0xff))]
        ;; rgba >>> 0 so we have an unsigned representation
    (unsigned-bit-shift-right (bit-or (bit-shift-left a 24) rgb) 0)))

(defn rgba-bytes-from-hex
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and returns an array with its r g b a values"
  [hex opacity]
  (let [rgb (js/parseInt (subs hex 1) 16)
        a (mth/floor (* (or opacity 1) 0xff))
        ;; rgba >>> 0 so we have an unsigned representation
        r (bit-shift-right rgb 16)
        g (bit-and (bit-shift-right rgb 8) 255)
        b (bit-and rgb 255)]
    [r g b a]))