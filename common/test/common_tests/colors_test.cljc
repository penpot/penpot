;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.colors-test
  (:require
   #?(:cljs [goog.color :as gcolors])
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

