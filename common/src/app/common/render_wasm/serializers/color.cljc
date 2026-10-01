;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.serializers.color
  (:require
   [app.common.math :as mth]))

(defn hex->u32argb
  "Takes a hex color in #rrggbb format, and an opacity value from 0 to 1 and
  returns its 32-bit argb representation."
  [hex opacity]
  (let [hex (or hex "#000000")
        rgb #?(:cljs (js/parseInt (subs hex 1) 16)
               :clj  (Integer/parseInt (subs hex 1) 16))
        ;; `mth/floor` returns a double on the JVM; bit ops need an int.
        a   (int (mth/floor (* (or opacity 1.0) 0xff)))]
    #?(:cljs (unsigned-bit-shift-right (bit-or (bit-shift-left a 24) rgb) 0)
       :clj  (bit-and (bit-or (bit-shift-left a 24) rgb) 0xffffffff))))
