;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-snap-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.snap :as gsn]
   [app.common.math :as mth]
   [clojure.test :as t]))

(t/deftest rect->snap-points-test
  (t/testing "Returns 5 snap points for a rect: 4 corners + center"
    (let [rect   (grc/make-rect 10 20 100 50)
          points (gsn/rect->snap-points rect)]
      (t/is (set? points))
      (t/is (= 5 (count points)))
      (t/is (every? gpt/point? points))))

  (t/testing "Snap points include correct corner coordinates"
    (let [rect   (grc/make-rect 0 0 100 100)
          points (gsn/rect->snap-points rect)]
      ;; Corners and center should be present
      (t/is (= 5 (count points)))
      ;; Check x-coordinates of corners
      (let [xs (set (map :x points))]
        (t/is (contains? xs 0))
        (t/is (contains? xs 100)))
      ;; Check y-coordinates of corners
      (let [ys (set (map :y points))]
        (t/is (contains? ys 0))
        (t/is (contains? ys 100)))
      ;; Center point should have x=50 and y=50
      (let [centers (filter #(and (mth/close? 50 (:x %)) (mth/close? 50 (:y %))) points)]
        (t/is (= 1 (count centers))))))

  (t/testing "nil rect returns nil"
    (t/is (nil? (gsn/rect->snap-points nil)))))

(t/deftest shape->snap-points-test
  (t/testing "Non-frame shape returns points + center"
    (let [points [(gpt/point 10 20) (gpt/point 110 20)
                  (gpt/point 110 70) (gpt/point 10 70)]
          shape  {:type :rect
                  :points points
                  :selrect (grc/make-rect 10 20 100 50)
                  :transform (gmt/matrix)}
          snap-pts (gsn/shape->snap-points shape)]
      (t/is (set? snap-pts))
      ;; At minimum, 4 corner points + 1 center = 5
      (t/is (>= (count snap-pts) 5)))))

(t/deftest guide->snap-points-test
  (t/testing "Guide on x-axis returns point at position"
    (let [guide  {:axis :x :position 100}
          frame  nil
          points (gsn/guide->snap-points guide frame)]
      (t/is (= 1 (count points)))
      (t/is (mth/close? 100 (:x (first points))))
      (t/is (mth/close? 0 (:y (first points))))))

  (t/testing "Guide on y-axis returns point at position"
    (let [guide  {:axis :y :position 200}
          frame  nil
          points (gsn/guide->snap-points guide frame)]
      (t/is (= 1 (count points)))
      (t/is (mth/close? 0 (:x (first points))))
      (t/is (mth/close? 200 (:y (first points)))))))
