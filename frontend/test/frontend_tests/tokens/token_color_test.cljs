;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-color-test
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.color :as wtc]
   [cljs.test :as t :include-macros true]))

(t/deftest color-bullet-color
  (t/testing "a valid color shows as hex with its opacity"
    (t/is (= {:color "#ff0000" :opacity 1} (wtc/color-bullet-color "#ff0000")))
    (t/is (= {:color "#ff0000" :opacity 1} (wtc/color-bullet-color "#f00")))
    (t/is (= {:color "#ff0000" :opacity 1} (wtc/color-bullet-color "red")))
    (t/is (= {:color "#ff0000" :opacity 0.5} (wtc/color-bullet-color "rgba(255, 0, 0, 0.5)")))
    (t/is (= {:color "#000000" :opacity 0} (wtc/color-bullet-color "transparent"))))

  (t/testing "invalid colors show no bullet"
    (t/is (nil? (wtc/color-bullet-color "not-a-color")))
    (t/is (nil? (wtc/color-bullet-color "ff0000")))
    (t/is (nil? (wtc/color-bullet-color "")))
    (t/is (nil? (wtc/color-bullet-color nil)))))

(t/deftest resolved-token-bullet-color
  (let [color-token (ctob/make-token :name "color.primary" :type :color :value "{color.base}")]
    (t/testing "a resolved color token shows its resolved color"
      (t/is (= {:color "#00ff00" :opacity 1}
               (wtc/resolved-token-bullet-color (assoc color-token :resolved-value "#00ff00")))))

    (t/testing "an unresolved color token shows no bullet"
      (t/is (nil? (wtc/resolved-token-bullet-color color-token)))
      (t/is (nil? (wtc/resolved-token-bullet-color (assoc color-token :resolved-value "not-a-color")))))

    (t/testing "tokens of other types show no bullet, even when their value is a color"
      (let [text (ctob/make-token :name "label.warning" :type :string :value "red")]
        (t/is (nil? (wtc/resolved-token-bullet-color (assoc text :resolved-value "red"))))))))
