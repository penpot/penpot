;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-shapes-text-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.text :as gte]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest position-data->rect-test
  (t/testing "Converts position data to a rect"
    (let [pd     {:x 100 :y 200 :width 80 :height 20}
          result (gte/position-data->rect pd)]
      (t/is (grc/rect? result))
      (t/is (mth/close? 100.0 (:x result)))
      (t/is (mth/close? 180.0 (:y result)))
      (t/is (mth/close? 80.0 (:width result)))
      (t/is (mth/close? 20.0 (:height result)))))

  (t/testing "Negative y still works"
    (let [pd     {:x 10 :y 5 :width 20 :height 10}
          result (gte/position-data->rect pd)]
      (t/is (mth/close? 10.0 (:x result)))
      (t/is (mth/close? -5.0 (:y result))))))

(t/deftest shape->rect-test
  (t/testing "Shape with position data returns bounding rect"
    (let [shape {:position-data [{:x 10 :y 50 :width 40 :height 10}
                                 {:x 10 :y 60 :width 30 :height 10}]}
          result (gte/shape->rect shape)]
      (t/is (grc/rect? result))
      (t/is (pos? (:width result)))
      (t/is (pos? (:height result)))))

  (t/testing "Shape without position data returns selrect"
    (let [selrect (grc/make-rect 10 20 100 50)
          shape   {:position-data nil :selrect selrect}
          result  (gte/shape->rect shape)]
      (t/is (= selrect result))))

  (t/testing "Shape with empty position data returns selrect"
    (let [selrect (grc/make-rect 10 20 100 50)
          shape   {:position-data [] :selrect selrect}
          result  (gte/shape->rect shape)]
      (t/is (= selrect result)))))

(t/deftest shape->bounds-test
  (t/testing "Shape with position data and identity transform"
    (let [shape {:position-data [{:x 10 :y 50 :width 40 :height 10}]
                 :selrect (grc/make-rect 10 40 40 10)
                 :transform (gmt/matrix)
                 :flip-x false :flip-y false}
          result (gte/shape->bounds shape)]
      (t/is (grc/rect? result))
      (t/is (pos? (:width result))))))

(t/deftest overlaps-position-data?-test
  (t/testing "Overlapping position data"
    (let [shape-points [(gpt/point 0 0) (gpt/point 100 0)
                        (gpt/point 100 100) (gpt/point 0 100)]
          shape        {:points shape-points}
          pd           [{:x 10 :y 30 :width 20 :height 10}]]
      (t/is (true? (gte/overlaps-position-data? shape pd)))))

  (t/testing "Non-overlapping position data"
    (let [shape-points [(gpt/point 0 0) (gpt/point 10 0)
                        (gpt/point 10 10) (gpt/point 0 10)]
          shape        {:points shape-points}
          pd           [{:x 200 :y 200 :width 20 :height 10}]]
      (t/is (false? (gte/overlaps-position-data? shape pd))))))

(t/deftest text-size-drifted-ignores-sub-pixel-gaps
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-width :width 100 :height 17} 100.5 17.5 1)))
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-height :width 100 :height 17} 100 17.5 1))))

(t/deftest text-size-drifted-checks-only-the-growing-axis
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-height :width 100 :height 17} 140 17 1))
        "auto-height width comes from the box, not the text")
  (t/is (gte/text-size-drifted? {:grow-type :auto-height :width 100 :height 17} 100 34 1))
  (t/is (gte/text-size-drifted? {:grow-type :auto-width :width 44 :height 17} 67 17 1))
  (t/is (gte/text-size-drifted? {:grow-type :auto-width :width 44 :height 17} 44 34 1)
        "auto-width grows in height too"))

(t/deftest text-size-drifted-treats-exactly-the-tolerance-as-rounding
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-height :width 100 :height 17} 100 18 1))
        "a 1px height gap is rounding at a 1px tolerance")
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-width :width 44 :height 17} 45 17 1))
        "a 1px width gap is rounding at a 1px tolerance")
  (t/is (gte/text-size-drifted? {:grow-type :auto-height :width 100 :height 17} 100 17.5 0.1)
        "a finer tolerance catches a sub-pixel gap"))

(t/deftest text-size-drifted-ignores-an-axis-that-fills-its-parent
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-height :width 100 :height 40
                                      :layout-item-v-sizing :fill}
                                     100 17 1))
        "a height set by the layout is not drift")
  (t/is (not (gte/text-size-drifted? {:grow-type :auto-width :width 200 :height 17
                                      :layout-item-h-sizing :fill}
                                     44 17 1))
        "a width set by the layout is not drift")
  (t/is (gte/text-size-drifted? {:grow-type :auto-width :width 200 :height 17
                                 :layout-item-h-sizing :fill}
                                44 34 1)
        "the axis that does not fill is still checked"))

(t/deftest text-size-drifted-ignores-other-grow-types
  (t/is (not (gte/text-size-drifted? {:grow-type :fixed :width 100 :height 17} 300 90 1)))
  (t/is (not (gte/text-size-drifted? {:width 100 :height 17} 300 90 1))))
