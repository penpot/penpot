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
    (t/is (= 400 (media/parse-font-weight "Mediaeval")))))

(t/deftest test-parse-font-style
  (t/testing "matches italic with proper boundaries"
    (t/is (= "italic" (media/parse-font-style "Roboto-Italic")))
    (t/is (= "italic" (media/parse-font-style "Roboto_Italic")))
    (t/is (= "italic" (media/parse-font-style "Roboto Italic")))
    (t/is (= "italic" (media/parse-font-style "Italic")))
    (t/is (= "italic" (media/parse-font-style "Roboto-BoldItalic"))))

  (t/testing "does not match italic embedded in words"
    (t/is (= "normal" (media/parse-font-style "Italica")))
    (t/is (= "normal" (media/parse-font-style "Roboto-Regular")))))

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

(t/deftest test-parse-font-file-metadata
  ;; Regression coverage for https://github.com/penpot/penpot/issues/10436:
  ;; when opentype.js parses a font binary but exposes no usable
  ;; preferredSubfamily/fontSubfamily, the upload resolves weight/style
  ;; from the filename through parse-font-file-metadata instead of
  ;; crashing on nil.
  (t/testing "usable opentype.js variant keeps current behavior"
    (t/is (= 700 (media/parse-font-weight "Bold")))
    (t/is (= "normal" (media/parse-font-style "Bold"))))

  (t/testing "missing variant recovers family, weight and style from filename"
    (let [{:keys [font-family font-weight font-style]}
          (media/parse-font-file-metadata "Pretendard-Medium.otf")]
      (t/is (= "Pretendard" font-family))
      (t/is (= 500 font-weight))
      (t/is (= "normal" font-style))))

  (t/testing "missing variant recovers weight and italic style from filename"
    (let [{:keys [font-weight font-style]}
          (media/parse-font-file-metadata "Pretendard-Bold-Italic.otf")]
      (t/is (= 700 font-weight))
      (t/is (= "italic" font-style))))

  (t/testing "filename fallback semantics are unchanged"
    (let [{:keys [font-family font-weight font-style]}
          (media/parse-font-file-metadata "Roboto-Bold.woff2")]
      (t/is (= "Roboto" font-family))
      (t/is (= 700 font-weight))
      (t/is (= "normal" font-style)))
    (let [{:keys [font-family font-weight font-style]}
          (media/parse-font-file-metadata "Pretendard.woff2")]
      (t/is (= "Pretendard" font-family))
      (t/is (= 400 font-weight))
      (t/is (= "normal" font-style)))))

(t/deftest test-resolve-parsed-font-metadata
  ;; Exercises the same decision the parsed-font upload path calls:
  ;; usable variant wins, missing/blank variants fall back to filename
  ;; metadata and leave :variant-name absent.
  (t/testing "usable variant keeps parsed family, weight, style and variant name"
    (let [m (media/resolve-parsed-font-metadata "Roboto" "Bold" "Roboto-Bold.ttf")]
      (t/is (= "Roboto" (:font-family m)))
      (t/is (= 700 (:font-weight m)))
      (t/is (= "normal" (:font-style m)))
      (t/is (= "Bold" (:variant-name m)))))

  (t/testing "usable variant takes precedence over filename metadata"
    (let [m (media/resolve-parsed-font-metadata "Roboto" "Bold" "Roboto-Light.ttf")]
      (t/is (= "Roboto" (:font-family m)))
      (t/is (= 700 (:font-weight m)))
      (t/is (= "normal" (:font-style m)))
      (t/is (= "Bold" (:variant-name m)))))

  (t/testing "missing variant recovers family, weight and style without variant name"
    (let [m (media/resolve-parsed-font-metadata "Pretendard" nil "Pretendard-Medium.otf")]
      (t/is (= "Pretendard" (:font-family m)))
      (t/is (= 500 (:font-weight m)))
      (t/is (= "normal" (:font-style m)))
      (t/is (not (contains? m :variant-name)))))

  (t/testing "blank variant falls back to filename metadata without variant name"
    (let [m (media/resolve-parsed-font-metadata "Pretendard" "  " "Pretendard-Medium.otf")]
      (t/is (= "Pretendard" (:font-family m)))
      (t/is (= 500 (:font-weight m)))
      (t/is (= "normal" (:font-style m)))
      (t/is (not (contains? m :variant-name)))))

  (t/testing "missing variant recovers italic style without variant name"
    (let [m (media/resolve-parsed-font-metadata "Pretendard" nil "Pretendard-Bold-Italic.otf")]
      (t/is (= "Pretendard" (:font-family m)))
      (t/is (= 700 (:font-weight m)))
      (t/is (= "italic" (:font-style m)))
      (t/is (not (contains? m :variant-name)))))

  (t/testing "missing parsed family falls back to filename family"
    (let [m (media/resolve-parsed-font-metadata nil nil "Pretendard-Medium.otf")]
      (t/is (= "Pretendard" (:font-family m)))
      (t/is (= 500 (:font-weight m)))
      (t/is (= "normal" (:font-style m)))
      (t/is (not (contains? m :variant-name))))))
