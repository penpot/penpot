;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.intersect :as gsin]
   [app.common.geom.shapes.transforms :as gsht]
   [app.common.math :as mth :refer [close?]]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [clojure.test :as t]))

(def default-path
  [{:command :move-to :params {:x 0 :y 0}}
   {:command :line-to :params {:x 20 :y 20}}
   {:command :line-to :params {:x 30 :y 30}}
   {:command :curve-to :params {:x 40 :y 40 :c1x 35 :c1y 35 :c2x 45 :c2y 45}}
   {:command :close-path}])

(defn create-test-shape
  ([type] (create-test-shape type {}))
  ([type params]
   (if (= type :path)
     (cts/setup-shape
      (into {:type :path
             :content (path/content (:content params default-path))}
            params))
     (cts/setup-shape
      (into {:type type
             :width 20
             :height 20}
            params)))))

(t/deftest transform-shapes
  (t/testing "Shape without modifiers should stay the same"
    (t/are [type]
           (let [shape-before (create-test-shape type)
                 shape-after  (gsh/transform-shape shape-before)]
             (= shape-before shape-after))

      :rect :path))

  (t/testing "Transform shape with translation modifiers"
    (doseq [type [:rect :path]]
      (let [modifiers    (ctm/move-modifiers (gpt/point 10 -10))
            shape-before (create-test-shape type {:modifiers modifiers})
            shape-after  (gsh/transform-shape shape-before)]

        (t/is (not= shape-before shape-after))

        (t/is (close? (get-in shape-before [:selrect :x])
                      (- 10 (get-in shape-after  [:selrect :x]))))

        (t/is (close? (get-in shape-before [:selrect :y])
                      (+ 10 (get-in shape-after  [:selrect :y]))))

        (t/is (close? (get-in shape-before [:selrect :width])
                      (get-in shape-after  [:selrect :width])))

        (t/is (close? (get-in shape-before [:selrect :height])
                      (get-in shape-after  [:selrect :height]))))))

  (t/testing "Transform with empty translation"
    (t/are [type]
           (let [modifiers {:displacement (gmt/matrix)}
                 shape-before (create-test-shape type {:modifiers modifiers})
                 shape-after  (gsh/transform-shape shape-before)]
             (t/are [prop]
                    (t/is (close? (get-in shape-before [:selrect prop])
                                  (get-in shape-after [:selrect prop])))
               :x :y :width :height :x1 :y1 :x2 :y2))
      :rect :path))

  (t/testing "Transform shape with resize modifiers"
    (t/are [type]
           (let [modifiers (ctm/resize-modifiers (gpt/point 2 2) (gpt/point 0 0))
                 shape-before (create-test-shape type {:modifiers modifiers})
                 shape-after  (gsh/transform-shape shape-before)]
             (t/is (not= shape-before shape-after))

             (t/is (close? (get-in shape-before [:selrect :x])
                           (get-in shape-after  [:selrect :x])))

             (t/is (close? (get-in shape-before [:selrect :y])
                           (get-in shape-after  [:selrect :y])))

             (t/is (close? (* 2 (get-in shape-before [:selrect :width]))
                           (get-in shape-after  [:selrect :width])))

             (t/is (close? (* 2 (get-in shape-before [:selrect :height]))
                           (get-in shape-after  [:selrect :height]))))
      :rect :path))

  (t/testing "Transform with empty resize"
    (t/are [type]
           (let [modifiers (ctm/resize-modifiers (gpt/point 1 1) (gpt/point 0 0))
                 shape-before (create-test-shape type {:modifiers modifiers})
                 shape-after  (gsh/transform-shape shape-before)]
             (t/are [prop]
                    (t/is (close? (get-in shape-before [:selrect prop])
                                  (get-in shape-after [:selrect prop])))
               :x :y :width :height :x1 :y1 :x2 :y2))
      :rect :path))

  (t/testing "Transform with resize=0"
    (let [modifiers    (ctm/resize-modifiers (gpt/point 0 0) (gpt/point 0 0))
          shape-before (create-test-shape :rect {:modifiers modifiers})
          shape-after  (gsh/transform-shape shape-before)]
      (t/is (close? 0.01 (get-in shape-after  [:selrect :width])))
      (t/is (close? 0.01 (get-in shape-after  [:selrect :height])))))

  (t/testing "Transform shape with rotation modifiers"
    (t/are [type]
           (let [shape-before (create-test-shape type)
                 modifiers (ctm/rotation-modifiers shape-before (gsh/shape->center shape-before) 30)
                 shape-before (assoc shape-before :modifiers modifiers)
                 shape-after  (gsh/transform-shape shape-before)]

             (t/is (close? (get-in shape-before [:selrect :x])
                           (get-in shape-after  [:selrect :x])))

             (t/is (close? (get-in shape-before [:selrect :y])
                           (get-in shape-after  [:selrect :y])))

             (t/is (= (count (:points shape-before)) (count (:points shape-after))))

             (for [idx (range 0 (count (:point shape-before)))]
               (do (t/is (not (close? (get-in shape-before [:points idx :x])
                                      (get-in shape-after [:points idx :x]))))
                   (t/is (not (close? (get-in shape-before [:points idx :y])
                                      (get-in shape-after [:points idx :y])))))))
      :rect :path))

  (t/testing "Transform shape with rotation = 0 should leave equal selrect"
    (t/are [type]
           (let [shape-before (create-test-shape type)
                 modifiers (ctm/rotation-modifiers shape-before (gsh/shape->center shape-before) 0)
                 shape-after  (gsh/transform-shape (assoc shape-before :modifiers modifiers))]
             (t/are [prop]
                    (t/is (close? (get-in shape-before [:selrect prop])
                                  (get-in shape-after [:selrect prop])))
               :x :y :width :height :x1 :y1 :x2 :y2))
      :rect :path))

  (t/testing "Transform shape with invalid selrect fails gracefully"
    (t/are [type selrect]
           (let [modifiers    (ctm/move-modifiers 0 0)
                 shape-before (create-test-shape type {:selrect selrect})
                 shape-after  (gsh/transform-shape shape-before modifiers)]

             (t/is (grc/close-rect? (:selrect shape-before)
                                    (:selrect shape-after))))

      :rect (grc/make-rect 0 0 ##Inf ##Inf)
      :path (grc/make-rect 0 0 ##Inf ##Inf))))

(t/deftest points-to-selrect
  (let [points [(gpt/point 0.5 0.5)
                (gpt/point -1 -2)
                (gpt/point 20 65.2)
                (gpt/point 12 -10)]
        result (grc/points->rect points)
        expect {:x -1, :y -10, :width 21, :height 75.2}]

    (t/is (= (:x expect) (:x result)))
    (t/is (= (:y expect) (:y result)))
    (t/is (= (:width expect) (:width result)))
    (t/is (= (:height expect) (:height result)))))

(def g45 (mth/radians 45))

(t/deftest points-transform-matrix
  (t/testing "Transform matrix"
    (t/are [selrect points expected]
           (let [result (gsht/transform-points-matrix selrect points)]
             (t/is (gmt/close? expected result)))

      ;; No transformation
      (grc/make-rect 0 0 10 10)
      (-> (grc/make-rect 0 0 10 10)
          (grc/rect->points))
      (gmt/matrix)

      ;; Displacement
      (grc/make-rect 0 0 10 10)
      (-> (grc/make-rect 20 20 10 10)
          (grc/rect->points))
      (gmt/matrix 1 0 0 1 20 20)

      ;; Resize
      (grc/make-rect 0 0 10 10)
      (-> (grc/make-rect 0 0 20 40)
          (grc/rect->points))
      (gmt/matrix 2 0 0 4 0 0)

      ;; Displacement+Resize
      (grc/make-rect 0 0 10 10)
      (-> (grc/make-rect 10 10 20 40)
          (grc/rect->points))
      (gmt/matrix 2 0 0 4 10 10)

      ;; Rotation
      (grc/make-rect 0 0 10 10)
      (-> (grc/make-rect 0 0 10 10)
          (grc/rect->points)
          (gsh/transform-points (gmt/rotate-matrix 45)))
      (gmt/matrix (mth/cos g45) (mth/sin g45) (- (mth/sin g45)) (mth/cos g45) 0 0)

      ;; Rotation + Resize
      (grc/make-rect 0 0 10 10)
      (-> (grc/make-rect 0 0 20 40)
          (grc/rect->points)
          (gsh/transform-points (gmt/rotate-matrix 45)))
      (gmt/matrix (* (mth/cos g45) 2) (* (mth/sin g45) 2) (* (- (mth/sin g45)) 4) (* (mth/cos g45) 4) 0 0))))


(t/deftest shape-has-point-pred
  (let [{:keys [points] :as shape} (create-test-shape :rect {})
        point1 (first points)
        point2 (update point1 :x - 100)]
    (t/is (true? (gsin/fast-has-point? shape point1)))
    (t/is (true? (gsin/slow-has-point? shape point1)))
    (t/is (false? (gsin/fast-has-point? shape point2)))
    (t/is (false? (gsin/fast-has-point? shape point2)))))
