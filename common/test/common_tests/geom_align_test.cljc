;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-align-test
  (:require
   [app.common.geom.align :as gal]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest valid-align-axis-test
  (t/testing "All expected axes are valid"
    (doseq [axis [:hleft :hcenter :hright :vtop :vcenter :vbottom]]
      (t/is (contains? gal/valid-align-axis axis))))

  (t/testing "Invalid axes are not in the set"
    (t/is (not (contains? gal/valid-align-axis :horizontal)))
    (t/is (not (contains? gal/valid-align-axis :vertical)))
    (t/is (not (contains? gal/valid-align-axis nil)))))

(t/deftest calc-align-pos-test
  (let [wrapper {:x 10 :y 20 :width 100 :height 50}
        rect    {:x 200 :y 300 :width 400 :height 200}]

    (t/testing ":hleft aligns wrapper's left edge to rect's left"
      (let [pos (gal/calc-align-pos wrapper rect :hleft)]
        (t/is (mth/close? 200.0 (:x pos)))
        (t/is (mth/close? 20.0 (:y pos)))))

    (t/testing ":hcenter centers wrapper horizontally in rect"
      (let [pos (gal/calc-align-pos wrapper rect :hcenter)]
        ;; center of rect = 200 + 400/2 = 400
        ;; wrapper center = pos.x + 100/2 = pos.x + 50
        ;; pos.x = 400 - 50 = 350
        (t/is (mth/close? 350.0 (:x pos)))
        (t/is (mth/close? 20.0 (:y pos)))))

    (t/testing ":hright aligns wrapper's right edge to rect's right"
      (let [pos (gal/calc-align-pos wrapper rect :hright)]
        ;; rect right = 200 + 400 = 600
        ;; pos.x = 600 - 100 = 500
        (t/is (mth/close? 500.0 (:x pos)))
        (t/is (mth/close? 20.0 (:y pos)))))

    (t/testing ":vtop aligns wrapper's top to rect's top"
      (let [pos (gal/calc-align-pos wrapper rect :vtop)]
        (t/is (mth/close? 10.0 (:x pos)))
        (t/is (mth/close? 300.0 (:y pos)))))

    (t/testing ":vcenter centers wrapper vertically in rect"
      (let [pos (gal/calc-align-pos wrapper rect :vcenter)]
        ;; center of rect = 300 + 200/2 = 400
        ;; wrapper center = pos.y + 50/2 = pos.y + 25
        ;; pos.y = 400 - 25 = 375
        (t/is (mth/close? 10.0 (:x pos)))
        (t/is (mth/close? 375.0 (:y pos)))))

    (t/testing ":vbottom aligns wrapper's bottom to rect's bottom"
      (let [pos (gal/calc-align-pos wrapper rect :vbottom)]
        ;; rect bottom = 300 + 200 = 500
        ;; pos.y = 500 - 50 = 450
        (t/is (mth/close? 10.0 (:x pos)))
        (t/is (mth/close? 450.0 (:y pos)))))))

(t/deftest valid-dist-axis-test
  (t/testing "Valid distribution axes"
    (t/is (contains? gal/valid-dist-axis :horizontal))
    (t/is (contains? gal/valid-dist-axis :vertical))
    (t/is (= 2 (count gal/valid-dist-axis)))))

(t/deftest adjust-to-viewport-test
  (t/testing "Adjusts rect to fit viewport with matching aspect ratio"
    (let [viewport {:width 1920 :height 1080}
          srect    {:x 0 :y 0 :width 1920 :height 1080}
          result   (gal/adjust-to-viewport viewport srect)]
      (t/is (some? result))
      (t/is (number? (:x result)))
      (t/is (number? (:y result)))
      (t/is (number? (:width result)))
      (t/is (number? (:height result)))))

  (t/testing "Adjusts with padding"
    (let [viewport {:width 1920 :height 1080}
          srect    {:x 100 :y 100 :width 400 :height 300}
          result   (gal/adjust-to-viewport viewport srect {:padding 50})]
      (t/is (some? result))
      (t/is (pos? (:width result)))
      (t/is (pos? (:height result)))))

  (t/testing "min-zoom constraint is applied"
    (let [viewport {:width 1920 :height 1080}
          srect    {:x 0 :y 0 :width 100 :height 100}
          result   (gal/adjust-to-viewport viewport srect {:min-zoom 0.5})]
      (t/is (some? result)))))
