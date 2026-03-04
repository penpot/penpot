;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
