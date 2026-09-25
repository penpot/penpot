;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.fonts-test
  "Tests for the upload metadata resolution in app.main.data.fonts."
  (:require
   [app.main.data.fonts :as df]
   [cljs.test :as t :include-macros true]))

(t/deftest resolve-font-metadata-prefers-opentype-values
  (t/testing "opentype.js values win over the filename"
    (t/is (= {:font-family "Inter"
              :font-weight 700
              :font-style "normal"
              :variant-name "Bold"}
             (df/resolve-font-metadata {:family "Inter" :variant "Bold"}
                                       "Inter.ttf")))))

(t/deftest resolve-font-metadata-falls-back-to-filename
  (t/testing "derives weight and style from the filename when the variant is missing"
    (t/is (= {:font-family "Pretendard"
              :font-weight 600
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {:family "Pretendard" :variant nil}
                                       "Pretendard-SemiBold.otf"))))

  (t/testing "derives weight and style from the filename when the variant is blank"
    (t/is (= {:font-family "Lato"
              :font-weight 300
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {:family "Lato" :variant ""}
                                       "Lato-Light.ttf"))))

  (t/testing "derives the family from the filename when it is missing"
    (t/is (= {:font-family "Blender Inter"
              :font-weight 700
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {:family nil :variant nil}
                                       "Blender-Inter-Bold.ttf"))))

  (t/testing "derives family, weight and style from the filename when nothing is available"
    (t/is (= {:font-family "Pretendard"
              :font-weight 600
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {} "Pretendard-SemiBold.woff2"))))

  (t/testing "derives italic from the filename"
    (t/is (= {:font-family "Roboto"
              :font-weight 400
              :font-style "italic"
              :variant-name nil}
             (df/resolve-font-metadata {} "Roboto-Italic.ttf"))))

  (t/testing "handles filenames without extension"
    (t/is (= {:font-family "Lato"
              :font-weight 300
              :font-style "normal"
              :variant-name nil}
             (df/resolve-font-metadata {} "Lato-Light")))))
