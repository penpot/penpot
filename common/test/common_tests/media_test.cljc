;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.media-test
  (:require
   [app.common.media :as media]
   [clojure.test :as t]))

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
