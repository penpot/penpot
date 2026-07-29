;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.geom-shapes-strokes-test
  (:require
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.geom.shapes.strokes :as gss]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [clojure.test :as t]))

(defn- make-open-path-with-stroke-cap
  [cap-type stroke-width]
  (cts/setup-shape
   {:type :path
    :content (path/content [{:command :move-to :params {:x 0 :y 100}}
                            {:command :curve-to :params {:x 200 :y 100 :c1x 0 :c1y -50 :c2x 200 :c2y -50}}])
    :strokes [{:stroke-color "#FF0000"
               :stroke-width stroke-width
               :stroke-alignment :center
               :stroke-cap-start cap-type
               :stroke-cap-end cap-type}]}))

(t/deftest stroke-cap-marker-padding-test
  (t/testing "Path with triangle-arrow caps should have enough padding to contain markers"
    ;; For triangle-arrow with strokeWidth=3:
    ;; markerHeight=8.5, viewBox 0 0 3 6, scale=min(8.5/3,8.5/6)*3=4.25
    ;; lateral extent = 3 * 4.25 = 12.75 user units per side
    ;; So padding should be > 12 user units
    (let [shape   (make-open-path-with-stroke-cap :triangle-arrow 3)
          padding (gsb/calculate-padding shape)]
      (t/is (> (:horizontal padding) 12)
            "Horizontal padding should accommodate triangle-arrow marker lateral extent")
      (t/is (> (:vertical padding) 12)
            "Vertical padding should accommodate triangle-arrow marker lateral extent")))

  (t/testing "Path with line-arrow caps should have enough padding to contain markers"
    (let [shape   (make-open-path-with-stroke-cap :line-arrow 3)
          padding (gsb/calculate-padding shape)]
      (t/is (> (:horizontal padding) 12)
            "Horizontal padding should accommodate line-arrow marker lateral extent")
      (t/is (> (:vertical padding) 12)
            "Vertical padding should accommodate line-arrow marker lateral extent")))

  (t/testing "Path with diamond-marker caps should have enough padding"
    ;; diamond-marker: markerWidth=6, viewBox 0 0 6 6, scale=min(6/6,6/6)*3=3
    ;; lateral extent = 3 * 3 = 9 user units per side
    (let [shape   (make-open-path-with-stroke-cap :diamond-marker 3)
          padding (gsb/calculate-padding shape)]
      (t/is (> (:horizontal padding) 8)
            "Horizontal padding should accommodate diamond-marker lateral extent")
      (t/is (> (:vertical padding) 8)
            "Vertical padding should accommodate diamond-marker lateral extent")))

  (t/testing "Closed path with marker caps should not have extra marker padding"
    (let [shape (cts/setup-shape
                 {:type :path
                  :content (path/content [{:command :move-to :params {:x 0 :y 0}}
                                          {:command :line-to :params {:x 100 :y 0}}
                                          {:command :close-path}])
                  :strokes [{:stroke-color "#FF0000"
                             :stroke-width 3
                             :stroke-alignment :center
                             :stroke-cap-start :triangle-arrow
                             :stroke-cap-end :triangle-arrow}]})
          padding (gsb/calculate-padding shape)]
      ;; Closed path: marker caps don't apply, so padding stays small
      (t/is (<= (:horizontal padding) 5)
            "Closed path should not have extra marker padding"))))

(t/deftest update-stroke-width-test
  (t/testing "Scale a stroke by 2"
    (let [stroke {:stroke-width 4 :stroke-color "#000"}
          scaled (gss/update-stroke-width stroke 2)]
      (t/is (= 8 (:stroke-width scaled)))
      (t/is (= "#000" (:stroke-color scaled)))))

  (t/testing "Scale by 1 preserves width"
    (let [stroke {:stroke-width 4}
          scaled (gss/update-stroke-width stroke 1)]
      (t/is (= 4 (:stroke-width scaled)))))

  (t/testing "Scale by 0 zeroes width"
    (let [stroke {:stroke-width 4}
          scaled (gss/update-stroke-width stroke 0)]
      (t/is (= 0 (:stroke-width scaled))))))

(t/deftest update-strokes-width-test
  (t/testing "Scale all strokes on a shape"
    (let [shape  {:strokes [{:stroke-width 2 :stroke-color "#aaa"}
                            {:stroke-width 5 :stroke-color "#bbb"}]}
          scaled (gss/update-strokes-width shape 3)
          s1     (first (:strokes scaled))
          s2     (second (:strokes scaled))]
      (t/is (= 6 (:stroke-width s1)))
      (t/is (= "#aaa" (:stroke-color s1)))
      (t/is (= 15 (:stroke-width s2)))
      (t/is (= "#bbb" (:stroke-color s2)))))

  (t/testing "Empty strokes stays empty"
    (let [shape  {:strokes []}
          scaled (gss/update-strokes-width shape 2)]
      (t/is (empty? (:strokes scaled)))))

  (t/testing "Shape with no :strokes key returns empty vector (mapv on nil)"
    (let [scaled (gss/update-strokes-width {} 2)]
      (t/is (= [] (:strokes scaled))))))
