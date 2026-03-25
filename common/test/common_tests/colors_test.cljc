;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.colors-test
  (:require
   #?(:cljs [goog.color :as gcolors])
   [app.common.colors :as c]
   [app.common.math :as mth]
   [app.common.types.color :as colors]
   [clojure.test :as t]))

(t/deftest valid-hex-color
  (t/is (false? (colors/valid-hex-color? nil)))
  (t/is (false? (colors/valid-hex-color? "")))
  (t/is (false? (colors/valid-hex-color? "#")))
  (t/is (false? (colors/valid-hex-color? "#qqqqqq")))
  (t/is (true? (colors/valid-hex-color? "#aaa")))
  (t/is (false? (colors/valid-hex-color? "#aaaa")))
  (t/is (true? (colors/valid-hex-color? "#fabada"))))

(t/deftest valid-rgb-color
  (t/is (false? (colors/valid-rgb-color? nil)))
  (t/is (false? (colors/valid-rgb-color? "")))
  (t/is (false? (colors/valid-rgb-color? "()")))
  (t/is (true? (colors/valid-rgb-color? "(255, 30, 30)")))
  (t/is (true? (colors/valid-rgb-color? "rgb(255, 30, 30)"))))

(t/deftest rgb-to-str
  (t/is (= "rgb(1,2,3)" (colors/rgb->str [1 2 3])))
  (t/is (= "rgba(1,2,3,4)" (colors/rgb->str [1 2 3 4]))))

(t/deftest rgb-to-hsv
  ;; (prn (colors/rgb->hsv [1 2 3]))
  ;; (prn (gcolors/rgbToHsv 1 2 3))
  (t/is (= [210.0 0.6666666666666666 3.0] (colors/rgb->hsv [1.0 2.0 3.0])))
  #?(:cljs (t/is (= (colors/rgb->hsv [1 2 3]) (vec (gcolors/rgbToHsv 1 2 3))))))

(t/deftest hsv-to-rgb
  (t/is (= [1 2 3]
           (colors/hsv->rgb [210 0.6666666666666666 3])))
  #?(:cljs
     (t/is (= (colors/hsv->rgb [210 0.6666666666666666 3])
              (vec (gcolors/hsvToRgb 210 0.6666666666666666 3))))))

(t/deftest rgb-to-hex
  (t/is (= "#010203" (colors/rgb->hex [1 2 3]))))

(t/deftest hex-to-rgb
  (t/is (= [0 0 0] (colors/hex->rgb "#kkk")))
  (t/is (= [1 2 3] (colors/hex->rgb "#010203"))))

(t/deftest format-hsla
  (t/is (= "210, 50%, 0.78%, 1" (colors/format-hsla [210.0 0.5 0.00784313725490196 1])))
  (t/is (= "220, 5%, 30%, 0.8" (colors/format-hsla [220.0 0.05 0.3 0.8]))))

(t/deftest format-rgba
  (t/is (= "210, 199, 12, 0.08" (colors/format-rgba [210 199 12 0.08])))
  (t/is (= "210, 199, 12, 1" (colors/format-rgba [210 199 12 1]))))

(t/deftest rgb-to-hsl
  (t/is (= [210.0 0.5 0.00784313725490196] (colors/rgb->hsl [1 2 3])))
  #?(:cljs (t/is (= (colors/rgb->hsl [1 2 3])
                    (vec (gcolors/rgbToHsl 1 2 3))))))

(t/deftest hsl-to-rgb
  (t/is (= [1 2 3] (colors/hsl->rgb [210.0 0.5 0.00784313725490196])))
  (t/is (= [210.0 0.5 0.00784313725490196] (colors/rgb->hsl [1 2 3])))
  #?(:cljs (t/is (= (colors/hsl->rgb [210 0.5 0.00784313725490196])
                    (vec (gcolors/hslToRgb 210 0.5 0.00784313725490196))))))

(t/deftest expand-hex
  (t/is (= "aaaaaa" (colors/expand-hex "a")))
  (t/is (= "aaaaaa" (colors/expand-hex "aa")))
  (t/is (= "aaaaaa" (colors/expand-hex "aaa")))
  (t/is (= "aaaa" (colors/expand-hex "aaaa"))))

(t/deftest prepend-hash
  (t/is "#aaa" (colors/prepend-hash "aaa"))
  (t/is "#aaa" (colors/prepend-hash "#aaa")))

(t/deftest remove-hash
  (t/is "aaa" (colors/remove-hash "aaa"))
  (t/is "aaa" (colors/remove-hash "#aaa")))

(t/deftest color-string-pred
  (t/is (true? (colors/color-string? "#aaa")))
  (t/is (true? (colors/color-string? "(10,10,10)")))
  (t/is (true? (colors/color-string? "rgb(10,10,10)")))
  (t/is (true? (colors/color-string? "magenta")))
  (t/is (false? (colors/color-string? nil)))
  (t/is (false? (colors/color-string? "")))
  (t/is (false? (colors/color-string? "kkkkkk"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; app.common.colors tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Predicates and parsing

(t/deftest ac-valid-hex-color
  (t/is (true?  (c/valid-hex-color? "#000000")))
  (t/is (true?  (c/valid-hex-color? "#FFFFFF")))
  (t/is (true?  (c/valid-hex-color? "#fabada")))
  (t/is (true?  (c/valid-hex-color? "#aaa")))
  (t/is (false? (c/valid-hex-color? nil)))
  (t/is (false? (c/valid-hex-color? "")))
  (t/is (false? (c/valid-hex-color? "#")))
  (t/is (false? (c/valid-hex-color? "#qqqqqq")))
  (t/is (false? (c/valid-hex-color? "#aaaa")))
  (t/is (false? (c/valid-hex-color? "fabada"))))

(t/deftest ac-parse-rgb
  (t/is (= [255 30 30]  (c/parse-rgb "rgb(255, 30, 30)")))
  (t/is (= [255 30 30]  (c/parse-rgb "(255, 30, 30)")))
  (t/is (= [0 0 0]      (c/parse-rgb "(0,0,0)")))
  (t/is (= [255 255 255] (c/parse-rgb "rgb(255,255,255)")))
  ;; Values out of 0-255 range return nil
  (t/is (nil? (c/parse-rgb "rgb(256, 0, 0)")))
  (t/is (nil? (c/parse-rgb "rgb(0, -1, 0)")))
  (t/is (nil? (c/parse-rgb "not-a-color")))
  (t/is (nil? (c/parse-rgb "#fabada"))))

(t/deftest ac-valid-rgb-color
  (t/is (true?  (c/valid-rgb-color? "rgb(255, 30, 30)")))
  (t/is (true?  (c/valid-rgb-color? "(255,30,30)")))
  (t/is (false? (c/valid-rgb-color? nil)))
  (t/is (false? (c/valid-rgb-color? "")))
  (t/is (false? (c/valid-rgb-color? "#fabada")))
  (t/is (false? (c/valid-rgb-color? "rgb(300,0,0)"))))

;; --- Core conversions

(t/deftest ac-rgb-to-str
  (t/is (= "rgb(1,2,3)"     (c/rgb->str [1 2 3])))
  (t/is (= "rgba(1,2,3,0.5)" (c/rgb->str [1 2 3 0.5])))
  (t/is (= "rgb(0,0,0)"     (c/rgb->str [0 0 0])))
  (t/is (= "rgb(255,255,255)" (c/rgb->str [255 255 255]))))

(t/deftest ac-hex-to-rgb
  (t/is (= [0 0 0]       (c/hex->rgb "#000000")))
  (t/is (= [255 255 255] (c/hex->rgb "#ffffff")))
  (t/is (= [1 2 3]       (c/hex->rgb "#010203")))
  (t/is (= [250 186 218] (c/hex->rgb "#fabada")))
  ;; Invalid hex falls back to [0 0 0]
  (t/is (= [0 0 0] (c/hex->rgb "#kkk"))))

(t/deftest ac-rgb-to-hex
  (t/is (= "#000000" (c/rgb->hex [0 0 0])))
  (t/is (= "#ffffff" (c/rgb->hex [255 255 255])))
  (t/is (= "#010203" (c/rgb->hex [1 2 3])))
  (t/is (= "#fabada" (c/rgb->hex [250 186 218]))))

(t/deftest ac-hex-to-rgb-roundtrip
  (t/is (= [250 186 218] (c/hex->rgb (c/rgb->hex [250 186 218]))))
  (t/is (= [10 20 30]    (c/hex->rgb (c/rgb->hex [10 20 30])))))

(t/deftest ac-rgb-to-hsv
  ;; Achromatic black
  (let [[h s v] (c/rgb->hsv [0 0 0])]
    (t/is (= 0 h))
    (t/is (= 0 s))
    (t/is (= 0 v)))
  ;; Red: h=0, s=1, v=255
  (let [[h s v] (c/rgb->hsv [255 0 0])]
    (t/is (mth/close? h 0.0))
    (t/is (mth/close? s 1.0))
    (t/is (= 255 v)))
  ;; Blue: h=240, s=1, v=255
  (let [[h s v] (c/rgb->hsv [0 0 255])]
    (t/is (mth/close? h 240.0))
    (t/is (mth/close? s 1.0))
    (t/is (= 255 v)))
  ;; Achromatic gray: h=0, s=0, v=128
  (let [[h s v] (c/rgb->hsv [128 128 128])]
    (t/is (= 0 h))
    (t/is (= 0 s))
    (t/is (= 128 v))))

(t/deftest ac-hsv-to-rgb
  (t/is (= [0 0 0]       (c/hsv->rgb [0 0 0])))
  (t/is (= [255 255 255] (c/hsv->rgb [0 0 255])))
  (t/is (= [1 2 3]       (c/hsv->rgb [210 0.6666666666666666 3])))
  ;; Achromatic (s=0)
  (let [[r g b] (c/hsv->rgb [0 0 128])]
    (t/is (= r g b 128))))

(t/deftest ac-rgb-to-hsv-roundtrip
  (let [orig [100 150 200]
        [h s v] (c/rgb->hsv orig)
        result  (c/hsv->rgb [h s v])]
    ;; Roundtrip may have rounding of ±1
    (t/is (every? true? (map #(< (mth/abs (- %1 %2)) 2) orig result)))))

(t/deftest ac-rgb-to-hsl
  ;; Black: h=0, s=0.0, l=0.0 (s is 0.0 not 0 on JVM, and ##NaN for white)
  (let [[h s l] (c/rgb->hsl [0 0 0])]
    (t/is (= 0 h))
    (t/is (mth/close? l 0.0)))
  ;; White: h=0, s=##NaN (achromatic), l=1.0
  (let [[_ _ l] (c/rgb->hsl [255 255 255])]
    (t/is (mth/close? l 1.0)))
  ;; Red [255 0 0] → hue=0, saturation=1, lightness=0.5
  (let [[h s l] (c/rgb->hsl [255 0 0])]
    (t/is (mth/close? h 0.0))
    (t/is (mth/close? s 1.0))
    (t/is (mth/close? l 0.5))))

(t/deftest ac-hsl-to-rgb
  (t/is (= [0 0 0]       (c/hsl->rgb [0 0 0])))
  (t/is (= [255 255 255] (c/hsl->rgb [0 0 1])))
  (t/is (= [1 2 3]       (c/hsl->rgb [210.0 0.5 0.00784313725490196])))
  ;; Achromatic (s=0): all channels equal lightness*255
  (let [[r g b] (c/hsl->rgb [0 0 0.5])]
    (t/is (= r g b))))

(t/deftest ac-rgb-hsl-roundtrip
  (let [orig [100 150 200]
        hsl    (c/rgb->hsl orig)
        result (c/hsl->rgb hsl)]
    (t/is (every? true? (map #(< (mth/abs (- %1 %2)) 2) orig result)))))

(t/deftest ac-hex-to-hsv
  ;; Black: h=0, s=0, v=0 (integers on JVM)
  (let [[h s v] (c/hex->hsv "#000000")]
    (t/is (= 0 h))
    (t/is (= 0 s))
    (t/is (= 0 v)))
  ;; Red: h=0, s=1, v=255
  (let [[h s v] (c/hex->hsv "#ff0000")]
    (t/is (mth/close? h 0.0))
    (t/is (mth/close? s 1.0))
    (t/is (= 255 v))))

(t/deftest ac-hex-to-rgba
  (t/is (= [0 0 0 1.0]       (c/hex->rgba "#000000" 1.0)))
  (t/is (= [255 255 255 0.5] (c/hex->rgba "#ffffff" 0.5)))
  (t/is (= [1 2 3 0.8]       (c/hex->rgba "#010203" 0.8))))

(t/deftest ac-hex-to-hsl
  ;; Black: h=0, s=0.0, l=0.0
  (let [[h s l] (c/hex->hsl "#000000")]
    (t/is (= 0 h))
    (t/is (mth/close? s 0.0))
    (t/is (mth/close? l 0.0)))
  ;; Invalid hex falls back to [0 0 0]
  (let [[h _ _] (c/hex->hsl "invalid")]
    (t/is (= 0 h))))

(t/deftest ac-hex-to-hsla
  ;; Black + full opacity: h=0, s=0.0, l=0.0, a=1.0
  (let [[h s l a] (c/hex->hsla "#000000" 1.0)]
    (t/is (= 0 h))
    (t/is (mth/close? s 0.0))
    (t/is (mth/close? l 0.0))
    (t/is (= a 1.0)))
  ;; White + half opacity: l=1.0, a=0.5
  (let [[_ _ l a] (c/hex->hsla "#ffffff" 0.5)]
    (t/is (mth/close? l 1.0))
    (t/is (= a 0.5))))

(t/deftest ac-hsl-to-hex
  (t/is (= "#000000" (c/hsl->hex [0 0 0])))
  (t/is (= "#ffffff" (c/hsl->hex [0 0 1])))
  (t/is (= "#ff0000" (c/hsl->hex [0 1 0.5]))))

(t/deftest ac-hsl-to-hsv
  ;; Black: stays [0 0 0]
  (let [[h s v] (c/hsl->hsv [0 0 0])]
    (t/is (= 0 h))
    (t/is (= 0 s))
    (t/is (= 0 v)))
  ;; Red: hsl [0 1 0.5] → hsv h≈0, s≈1, v≈255
  (let [[h s v] (c/hsl->hsv [0 1 0.5])]
    (t/is (mth/close? h 0.0))
    (t/is (mth/close? s 1.0))
    (t/is (mth/close? v 255.0))))

(t/deftest ac-hsv-to-hex
  (t/is (= "#000000" (c/hsv->hex [0 0 0])))
  (t/is (= "#ffffff" (c/hsv->hex [0 0 255]))))

(t/deftest ac-hsv-to-hsl
  ;; Black
  (let [[h s l] (c/hsv->hsl [0 0 0])]
    (t/is (= 0 h))
    (t/is (mth/close? s 0.0))
    (t/is (mth/close? l 0.0)))
  ;; White: h=0, s=##NaN (achromatic), l=1.0
  (let [[_ _ l] (c/hsv->hsl [0 0 255])]
    (t/is (mth/close? l 1.0))))

(t/deftest ac-hex-to-lum
  ;; Black has luminance 0
  (t/is (= 0.0 (c/hex->lum "#000000")))
  ;; White has max luminance
  (let [lum (c/hex->lum "#ffffff")]
    (t/is (> lum 0)))
  ;; Luminance is non-negative
  (t/is (>= (c/hex->lum "#fabada") 0)))

;; --- Formatters

(t/deftest ac-format-hsla
  (t/is (= "210 50% 0.78% / 1"   (c/format-hsla [210.0 0.5 0.00784313725490196 1])))
  (t/is (= "220 5% 30% / 0.8"    (c/format-hsla [220.0 0.05 0.3 0.8])))
  (t/is (= "0 0% 0% / 0"         (c/format-hsla [0 0 0 0]))))

(t/deftest ac-format-rgba
  (t/is (= "210, 199, 12, 0.08" (c/format-rgba [210 199 12 0.08])))
  (t/is (= "0, 0, 0, 1"         (c/format-rgba [0 0 0 1])))
  (t/is (= "255, 255, 255, 0.5" (c/format-rgba [255 255 255 0.5]))))

;; --- String utilities

(t/deftest ac-expand-hex
  ;; Single char: repeated 6 times
  (t/is (= "aaaaaa" (c/expand-hex "a")))
  ;; Two chars: repeated as 3 pairs
  (t/is (= "aaaaaa" (c/expand-hex "aa")))
  ;; Three chars: each char doubled
  (t/is (= "aabbcc" (c/expand-hex "abc")))
  ;; Other lengths: returned as-is
  (t/is (= "aaaa"   (c/expand-hex "aaaa")))
  (t/is (= "aaaaaa" (c/expand-hex "aaaaaa"))))

(t/deftest ac-prepend-hash
  (t/is (= "#fabada" (c/prepend-hash "fabada")))
  ;; Already has hash: unchanged
  (t/is (= "#fabada" (c/prepend-hash "#fabada"))))

(t/deftest ac-remove-hash
  (t/is (= "fabada" (c/remove-hash "#fabada")))
  ;; No hash: unchanged
  (t/is (= "fabada" (c/remove-hash "fabada"))))

;; --- High-level predicates / parsing

(t/deftest ac-color-string
  (t/is (true?  (c/color-string? "#aaa")))
  (t/is (true?  (c/color-string? "#fabada")))
  (t/is (true?  (c/color-string? "rgb(10,10,10)")))
  (t/is (true?  (c/color-string? "(10,10,10)")))
  (t/is (true?  (c/color-string? "magenta")))
  (t/is (false? (c/color-string? nil)))
  (t/is (false? (c/color-string? "")))
  (t/is (false? (c/color-string? "notacolor"))))

(t/deftest ac-parse
  ;; Valid hex → normalized lowercase
  (t/is (= "#fabada" (c/parse "#fabada")))
  (t/is (= "#fabada" (c/parse "#FABADA")))
  ;; Short hex → expanded+normalized
  (t/is (= "#aaaaaa" (c/parse "#aaa")))
  ;; Hex without hash: normalize-hex is called, returns lowercase without adding #
  (t/is (= "fabada" (c/parse "fabada")))
  ;; Named color
  (t/is (= "#ff0000" (c/parse "red")))
  (t/is (= "#ff00ff" (c/parse "magenta")))
  ;; rgb() notation
  (t/is (= "#ff1e1e" (c/parse "rgb(255, 30, 30)")))
  ;; Invalid → nil
  (t/is (nil? (c/parse "notacolor")))
  (t/is (nil? (c/parse nil))))

;; --- next-rgb

(t/deftest ac-next-rgb
  ;; Increment blue channel
  (t/is (= [0 0 1]   (c/next-rgb [0 0 0])))
  (t/is (= [0 0 255] (c/next-rgb [0 0 254])))
  ;; Blue overflow: increment green, reset blue
  (t/is (= [0 1 0]   (c/next-rgb [0 0 255])))
  ;; Green overflow: increment red, reset green
  (t/is (= [1 0 0]   (c/next-rgb [0 255 255])))
  ;; White overflows: throws
  (t/is (thrown? #?(:clj Exception :cljs :default)
                 (c/next-rgb [255 255 255]))))

;; --- reduce-range

(t/deftest ac-reduce-range
  (t/is (= 0.5  (c/reduce-range 0.5 2)))
  (t/is (= 0.0  (c/reduce-range 0.1 2)))
  (t/is (= 0.25 (c/reduce-range 0.3 4)))
  (t/is (= 0.0  (c/reduce-range 0.0 10))))

;; --- Gradient helpers

(t/deftest ac-interpolate-color
  (let [c1 {:color "#000000" :opacity 0.0 :offset 0.0}
        c2 {:color "#ffffff" :opacity 1.0 :offset 1.0}]
    ;; At c1's offset → c1 with updated offset
    (let [result (c/interpolate-color c1 c2 0.0)]
      (t/is (= "#000000" (:color result)))
      (t/is (= 0.0 (:opacity result))))
    ;; At c2's offset → c2 with updated offset
    (let [result (c/interpolate-color c1 c2 1.0)]
      (t/is (= "#ffffff" (:color result)))
      (t/is (= 1.0 (:opacity result))))
    ;; At midpoint → gray
    (let [result (c/interpolate-color c1 c2 0.5)]
      (t/is (= "#7f7f7f" (:color result)))
      (t/is (mth/close? (:opacity result) 0.5)))))

(t/deftest ac-uniform-spread
  (let [c1 {:color "#000000" :opacity 0.0 :offset 0.0}
        c2 {:color "#ffffff" :opacity 1.0 :offset 1.0}
        stops (c/uniform-spread c1 c2 3)]
    (t/is (= 3 (count stops)))
    (t/is (= 0.0 (:offset (first stops))))
    (t/is (mth/close? 0.5 (:offset (second stops))))
    (t/is (= 1.0 (:offset (last stops))))))

(t/deftest ac-uniform-spread?
  (let [c1 {:color "#000000" :opacity 0.0 :offset 0.0}
        c2 {:color "#ffffff" :opacity 1.0 :offset 1.0}
        stops (c/uniform-spread c1 c2 3)]
    ;; A uniformly spread result should pass the predicate
    (t/is (true? (c/uniform-spread? stops))))
  ;; Manual non-uniform stops should not pass
  (let [stops [{:color "#000000" :opacity 0.0 :offset 0.0}
               {:color "#888888" :opacity 0.5 :offset 0.3}
               {:color "#ffffff" :opacity 1.0 :offset 1.0}]]
    (t/is (false? (c/uniform-spread? stops)))))

(t/deftest ac-interpolate-gradient
  (let [stops [{:color "#000000" :opacity 0.0 :offset 0.0}
               {:color "#ffffff" :opacity 1.0 :offset 1.0}]]
    ;; At start
    (let [result (c/interpolate-gradient stops 0.0)]
      (t/is (= "#000000" (:color result))))
    ;; At end
    (let [result (c/interpolate-gradient stops 1.0)]
      (t/is (= "#ffffff" (:color result))))
    ;; In the middle
    (let [result (c/interpolate-gradient stops 0.5)]
      (t/is (= "#7f7f7f" (:color result))))))

