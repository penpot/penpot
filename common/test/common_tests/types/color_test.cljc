;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.color-test
  (:require
   [app.common.math :as mth]
   [app.common.types.color :as colors]
   [clojure.test :as t]))

;; --- Predicates

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

;; --- Conversions

(t/deftest rgb-to-str
  (t/is (= "rgb(1,2,3)" (colors/rgb->str [1 2 3])))
  (t/is (= "rgba(1,2,3,4)" (colors/rgb->str [1 2 3 4]))))

(t/deftest rgb-to-hsv
  (t/is (= [210.0 0.6666666666666666 3.0] (colors/rgb->hsv [1.0 2.0 3.0]))))

(t/deftest hsv-to-rgb
  (t/is (= [1 2 3]
           (colors/hsv->rgb [210 0.6666666666666666 3]))))

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
  (t/is (= [210.0 0.5 0.00784313725490196] (colors/rgb->hsl [1 2 3]))))

(t/deftest hsl-to-rgb
  (t/is (= [1 2 3] (colors/hsl->rgb [210.0 0.5 0.00784313725490196])))
  (t/is (= [210.0 0.5 0.00784313725490196] (colors/rgb->hsl [1 2 3]))))

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

;; --- Gradient helpers

(t/deftest interpolate-color
  (t/testing "at c1 offset returns c1 color"
    (let [c1     {:color "#000000" :opacity 0.0 :offset 0.0}
          c2     {:color "#ffffff" :opacity 1.0 :offset 1.0}
          result (colors/interpolate-color c1 c2 0.0)]
      (t/is (= "#000000" (:color result)))
      (t/is (= 0.0 (:opacity result)))))
  (t/testing "at c2 offset returns c2 color"
    (let [c1     {:color "#000000" :opacity 0.0 :offset 0.0}
          c2     {:color "#ffffff" :opacity 1.0 :offset 1.0}
          result (colors/interpolate-color c1 c2 1.0)]
      (t/is (= "#ffffff" (:color result)))
      (t/is (= 1.0 (:opacity result)))))
  (t/testing "at midpoint returns interpolated gray"
    (let [c1     {:color "#000000" :opacity 0.0 :offset 0.0}
          c2     {:color "#ffffff" :opacity 1.0 :offset 1.0}
          result (colors/interpolate-color c1 c2 0.5)]
      (t/is (= "#7f7f7f" (:color result)))
      (t/is (mth/close? (:opacity result) 0.5)))))

(t/deftest uniform-spread
  (t/testing "produces correct count and offsets"
    (let [c1    {:color "#000000" :opacity 0.0 :offset 0.0}
          c2    {:color "#ffffff" :opacity 1.0 :offset 1.0}
          stops (colors/uniform-spread c1 c2 3)]
      (t/is (= 3 (count stops)))
      (t/is (= 0.0 (:offset (first stops))))
      (t/is (mth/close? 0.5 (:offset (second stops))))
      (t/is (= 1.0 (:offset (last stops))))))
  (t/testing "single stop returns a vector of one element (no division by zero)"
    (let [c1    {:color "#ff0000" :opacity 1.0 :offset 0.0}
          stops (colors/uniform-spread c1 c1 1)]
      (t/is (= 1 (count stops))))))

(t/deftest uniform-spread?
  (t/testing "uniformly spread stops are detected as uniform"
    (let [c1    {:color "#000000" :opacity 0.0 :offset 0.0}
          c2    {:color "#ffffff" :opacity 1.0 :offset 1.0}
          stops (colors/uniform-spread c1 c2 3)]
      (t/is (true? (colors/uniform-spread? stops)))))
  (t/testing "two-stop gradient is uniform by definition"
    (let [stops [{:color "#ff0000" :opacity 1.0 :offset 0.0}
                 {:color "#0000ff" :opacity 1.0 :offset 1.0}]]
      (t/is (true? (colors/uniform-spread? stops)))))
  (t/testing "stops with wrong offset are not uniform"
    (let [stops [{:color "#000000" :opacity 0.0 :offset 0.0}
                 {:color "#888888" :opacity 0.5 :offset 0.3}
                 {:color "#ffffff" :opacity 1.0 :offset 1.0}]]
      (t/is (false? (colors/uniform-spread? stops)))))
  (t/testing "stops with correct offset but wrong color are not uniform"
    (let [stops [{:color "#000000" :opacity 0.0 :offset 0.0}
                 {:color "#aaaaaa" :opacity 0.5 :offset 0.5}
                 {:color "#ffffff" :opacity 1.0 :offset 1.0}]]
      (t/is (false? (colors/uniform-spread? stops))))))

(t/deftest interpolate-gradient
  (t/testing "at start offset returns first stop color"
    (let [stops  [{:color "#000000" :opacity 0.0 :offset 0.0}
                  {:color "#ffffff" :opacity 1.0 :offset 1.0}]
          result (colors/interpolate-gradient stops 0.0)]
      (t/is (= "#000000" (:color result)))))
  (t/testing "at end offset returns last stop color"
    (let [stops  [{:color "#000000" :opacity 0.0 :offset 0.0}
                  {:color "#ffffff" :opacity 1.0 :offset 1.0}]
          result (colors/interpolate-gradient stops 1.0)]
      (t/is (= "#ffffff" (:color result)))))
  (t/testing "at midpoint returns interpolated gray"
    (let [stops  [{:color "#000000" :opacity 0.0 :offset 0.0}
                  {:color "#ffffff" :opacity 1.0 :offset 1.0}]
          result (colors/interpolate-gradient stops 0.5)]
      (t/is (= "#7f7f7f" (:color result)))))
  (t/testing "offset beyond last stop returns last stop color (nil idx guard)"
    (let [stops  [{:color "#000000" :opacity 0.0 :offset 0.0}
                  {:color "#ffffff" :opacity 1.0 :offset 0.5}]
          result (colors/interpolate-gradient stops 1.0)]
      (t/is (= "#ffffff" (:color result))))))

(t/deftest rgb-to-hsb
  ;; Achromatic black: brightness 0
  (let [[h s b] (colors/rgb->hsb [0 0 0])]
    (t/is (= 0 h))
    (t/is (= 0 s))
    (t/is (mth/close? b 0.0)))
  ;; Pure red: hue 0, full saturation, brightness 100
  (let [[h s b] (colors/rgb->hsb [255 0 0])]
    (t/is (mth/close? h 0.0))
    (t/is (mth/close? s 1.0))
    (t/is (mth/close? b 100.0)))
  ;; Pure white: brightness 100
  (let [[_ _ b] (colors/rgb->hsb [255 255 255])]
    (t/is (mth/close? b 100.0)))
  ;; Mid gray: brightness ~50.2
  (let [[_ _ b] (colors/rgb->hsb [128 128 128])]
    (t/is (mth/close? b (* (/ 128.0 255.0) 100.0)))))

(t/deftest hsb-to-rgb
  (t/is (= [0 0 0]       (colors/hsb->rgb [0 0 0])))
  (t/is (= [255 255 255] (colors/hsb->rgb [0 0 100])))
  ;; Pure red from HSB
  (let [[r g b] (colors/hsb->rgb [0 1 100])]
    (t/is (= 255 r))
    (t/is (= 0 g))
    (t/is (= 0 b))))

(t/deftest hex-to-hsb
  ;; Black
  (let [[h s b] (colors/hex->hsb "#000000")]
    (t/is (= 0 h))
    (t/is (= 0 s))
    (t/is (mth/close? b 0.0)))
  ;; White: brightness 100
  (let [[_ _ b] (colors/hex->hsb "#ffffff")]
    (t/is (mth/close? b 100.0)))
  ;; Red
  (let [[h s b] (colors/hex->hsb "#ff0000")]
    (t/is (mth/close? h 0.0))
    (t/is (mth/close? s 1.0))
    (t/is (mth/close? b 100.0))))

(t/deftest hsb-to-hex
  (t/is (= "#000000" (colors/hsb->hex [0 0 0])))
  (t/is (= "#ffffff" (colors/hsb->hex [0 0 100]))))

(t/deftest hsv-hsb-roundtrip
  ;; HSV brightness is 0-255, HSB brightness is 0-100. Round-trip
  ;; should reach the same triple within ±1 (integer rounding).
  (let [orig    [210.0 0.5 128]
        hsb     (colors/hsv->hsb orig)
        result  (colors/hsb->hsv hsb)]
    (t/is (mth/close? (nth orig 0) (nth result 0)))
    (t/is (mth/close? (nth orig 1) (nth result 1)))
    (t/is (< (mth/abs (- (nth orig 2) (nth result 2))) 2))))

(t/deftest rgb-hsb-roundtrip
  ;; RGB → HSB → RGB should land within ±1 per channel
  (let [orig    [100 150 200]
        hsb     (colors/rgb->hsb orig)
        result  (colors/hsb->rgb hsb)]
    (t/is (every? true? (map #(< (mth/abs (- %1 %2)) 2) orig result)))))

(t/deftest hex-hsb-roundtrip
  ;; HEX → HSB → HEX should preserve the color across the model swap
  (let [orig   "#fabada"
        hsb    (colors/hex->hsb orig)
        result (colors/hsb->hex hsb)]
    ;; Allow ±1 per channel after the round-trip due to integer rounding
    (let [[r1 g1 b1] (colors/hex->rgb orig)
          [r2 g2 b2] (colors/hex->rgb result)]
      (t/is (< (mth/abs (- r1 r2)) 2))
      (t/is (< (mth/abs (- g1 g2)) 2))
      (t/is (< (mth/abs (- b1 b2)) 2)))))
