;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.text-styles-test
  (:require
   [app.main.ui.shapes.text.styles :as sts]
   [cljs.test :refer [deftest is testing] :include-macros true]))

(deftest paragraph-set-min-width
  (testing "auto-width text with Fill width spans its box so alignment acts"
    (is (= "100%" (.-minWidth (sts/generate-paragraph-set-styles
                               {:grow-type :auto-width
                                :layout-item-h-sizing :fill})))))

  (testing "auto-width text without Fill width keeps its measured width"
    (is (nil? (.-minWidth (sts/generate-paragraph-set-styles
                           {:grow-type :auto-width}))))
    (is (nil? (.-minWidth (sts/generate-paragraph-set-styles
                           {:grow-type :auto-width
                            :layout-item-h-sizing :fix})))))

  (testing "other grow types span their box"
    (is (= "100%" (.-minWidth (sts/generate-paragraph-set-styles
                               {:grow-type :auto-height}))))
    (is (= "100%" (.-minWidth (sts/generate-paragraph-set-styles
                               {:grow-type :fixed}))))))
