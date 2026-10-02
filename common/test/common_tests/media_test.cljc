;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.media-test
  (:require
   [app.common.media :as media]
   [clojure.test :as t]))

(t/deftest test-parse-font-weight
  (t/testing "matches weight tokens with proper boundaries"
    (t/is (= 700 (media/parse-font-weight "Roboto-Bold")))
    (t/is (= 700 (media/parse-font-weight "Roboto_Bold")))
    (t/is (= 700 (media/parse-font-weight "Roboto Bold")))
    (t/is (= 700 (media/parse-font-weight "Bold")))
    (t/is (= 800 (media/parse-font-weight "Roboto-ExtraBold")))
    (t/is (= 600 (media/parse-font-weight "OpenSans-SemiBold")))
    (t/is (= 300 (media/parse-font-weight "Lato-Light")))
    (t/is (= 100 (media/parse-font-weight "Roboto-Thin")))
    (t/is (= 200 (media/parse-font-weight "Roboto-ExtraLight")))
    (t/is (= 500 (media/parse-font-weight "Roboto-Medium")))
    (t/is (= 900 (media/parse-font-weight "Roboto-Black"))))

  (t/testing "does not match weight tokens embedded in words"
    (t/is (= 400 (media/parse-font-weight "Boldini")))
    (t/is (= 400 (media/parse-font-weight "Lighthaus")))
    (t/is (= 400 (media/parse-font-weight "Blackwood")))
    (t/is (= 400 (media/parse-font-weight "Thinker")))
    (t/is (= 400 (media/parse-font-weight "Mediaeval"))))

  (t/testing "matches a weight glued to a style token"
    (t/is (= 700 (media/parse-font-weight "Roboto-BoldItalic")))
    (t/is (= 700 (media/parse-font-weight "Roboto-BoldOblique")))
    (t/is (= 600 (media/parse-font-weight "Roboto-SemiBoldItalic"))))

  (t/testing "handles nil and blank variants safely"
    (t/is (= 400 (media/parse-font-weight nil)))
    (t/is (= 400 (media/parse-font-weight "")))
    (t/is (= 400 (media/parse-font-weight "   ")))))

(t/deftest test-parse-font-style
  (t/testing "matches italic with proper boundaries"
    (t/is (= "italic" (media/parse-font-style "Roboto-Italic")))
    (t/is (= "italic" (media/parse-font-style "Roboto_Italic")))
    (t/is (= "italic" (media/parse-font-style "Roboto Italic")))
    (t/is (= "italic" (media/parse-font-style "Italic")))
    (t/is (= "italic" (media/parse-font-style "Roboto-BoldItalic"))))

  (t/testing "does not match italic embedded in words"
    (t/is (= "normal" (media/parse-font-style "Italica")))
    (t/is (= "normal" (media/parse-font-style "Roboto-Regular"))))

  (t/testing "treats oblique as italic"
    (t/is (= "italic" (media/parse-font-style "Roboto-Oblique")))
    (t/is (= "italic" (media/parse-font-style "Roboto-BoldOblique")))
    (t/is (= "italic" (media/parse-font-style "RobotoOblique"))))

  (t/testing "handles nil and blank variants safely"
    (t/is (= "normal" (media/parse-font-style nil)))
    (t/is (= "normal" (media/parse-font-style "")))
    (t/is (= "normal" (media/parse-font-style "   ")))))

(t/deftest test-parse-font-family
  (t/testing "strips weight and style tokens"
    (t/is (= "Roboto" (media/parse-font-family "Roboto-Bold")))
    (t/is (= "Roboto" (media/parse-font-family "Roboto_Bold")))
    (t/is (= "Roboto" (media/parse-font-family "Roboto Bold")))
    (t/is (= "Roboto" (media/parse-font-family "Roboto-Oblique")))
    (t/is (= "OpenSans" (media/parse-font-family "OpenSans-SemiBold")))
    (t/is (= "Lato" (media/parse-font-family "Lato-Light"))))

  (t/testing "separates a style token glued to a weight token"
    (t/is (= "Roboto" (media/parse-font-family "Roboto-BoldItalic")))
    (t/is (= "Roboto" (media/parse-font-family "Roboto-SemiBoldItalic")))
    (t/is (= "Roboto" (media/parse-font-family "Roboto-LightOblique")))
    (t/is (= "Roboto" (media/parse-font-family "Roboto-BlackOblique"))))

  (t/testing "does not strip tokens embedded in words"
    (t/is (= "Boldini" (media/parse-font-family "Boldini-Bold")))
    (t/is (= "Lighthaus" (media/parse-font-family "Lighthaus")))
    (t/is (= "Blackwood" (media/parse-font-family "Blackwood")))
    (t/is (= "Mediaeval" (media/parse-font-family "Mediaeval"))))

  (t/testing "keeps unknown tokens in the family"
    (t/is (= "Satoshi Variable" (media/parse-font-family "Satoshi-Variable"))))

  (t/testing "falls back to the base name when everything is stripped"
    (t/is (= "BoldItalic" (media/parse-font-family "BoldItalic")))))

(t/deftest test-first-nonblank-string
  (t/testing "returns the first nonblank string"
    (t/is (= "Bold" (media/first-nonblank-string "  " "Bold")))
    (t/is (= "Bold" (media/first-nonblank-string "Bold" "Light")))
    (t/is (= "Bold" (media/first-nonblank-string nil "Bold")))
    (t/is (= "Inter" (media/first-nonblank-string "" "Inter"))))

  (t/testing "returns nil when every candidate is blank or not a string"
    (t/is (nil? (media/first-nonblank-string nil "  ")))
    (t/is (nil? (media/first-nonblank-string)))
    (t/is (nil? (media/first-nonblank-string 42 "  ")))))

(t/deftest test-strip-image-extension
  (t/testing "removes extension from supported image files"
    (t/is (= (media/strip-image-extension "foo.png") "foo"))
    (t/is (= (media/strip-image-extension "foo.webp") "foo"))
    (t/is (= (media/strip-image-extension "foo.jpg") "foo"))
    (t/is (= (media/strip-image-extension "foo.jpeg") "foo"))
    (t/is (= (media/strip-image-extension "foo.svg") "foo"))
    (t/is (= (media/strip-image-extension "foo.gif") "foo")))

  (t/testing "does not remove extension for unsupported files"
    (t/is (= (media/strip-image-extension "foo.txt") "foo.txt"))
    (t/is (= (media/strip-image-extension "foo.bmp") "foo.bmp")))

  (t/testing "leaves filename intact when it has no extension"
    (t/is (= (media/strip-image-extension "README") "README"))))

(t/deftest test-font-display-variant
  (t/testing "preserves the foundry-supplied variant string verbatim"
    (t/is (= "Thin"          (media/font-display-variant "Thin" 100 "normal")))
    (t/is (= "SemiBold"      (media/font-display-variant "SemiBold" 600 "normal")))
    (t/is (= "Medium Oblique" (media/font-display-variant "Medium Oblique" 500 "italic")))
    (t/is (= "Ultra"         (media/font-display-variant "Ultra" 900 "normal"))))

  (t/testing "trims surrounding whitespace from upstream variant strings"
    (t/is (= "Bold" (media/font-display-variant "  Bold  " 700 "normal"))))

  (t/testing "ignores blank or nil variant strings"
    (t/is (= "Hairline"        (media/font-display-variant nil 100 "normal")))
    (t/is (= "Regular"         (media/font-display-variant ""  400 "normal")))
    (t/is (= "Bold"            (media/font-display-variant "  " 700 "normal")))
    (t/is (= "Bold Italic"     (media/font-display-variant nil 700 "italic"))))

  (t/testing "fallback covers every supported numeric weight"
    (t/is (= "Hairline"    (media/font-display-variant nil 100 "normal")))
    (t/is (= "Extra Light" (media/font-display-variant nil 200 "normal")))
    (t/is (= "Light"       (media/font-display-variant nil 300 "normal")))
    (t/is (= "Regular"     (media/font-display-variant nil 400 "normal")))
    (t/is (= "Medium"      (media/font-display-variant nil 500 "normal")))
    (t/is (= "Semi Bold"   (media/font-display-variant nil 600 "normal")))
    (t/is (= "Bold"        (media/font-display-variant nil 700 "normal")))
    (t/is (= "Extra Bold"  (media/font-display-variant nil 800 "normal")))
    (t/is (= "Black"       (media/font-display-variant nil 900 "normal")))
    (t/is (= "Extra Black" (media/font-display-variant nil 950 "normal"))))

  (t/testing "italic suffix only applied via the fallback path"
    (t/is (= "Italic"           (media/font-display-variant "Italic" 400 "italic")))
    (t/is (= "Regular Italic"   (media/font-display-variant nil 400 "italic"))))

  (t/testing "stored variant survives even when its derived weight disagrees"
    (t/is (= "Ultra" (media/font-display-variant "Ultra" 400 "normal")))))
