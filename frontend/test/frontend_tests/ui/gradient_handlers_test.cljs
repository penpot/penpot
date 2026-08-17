;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.gradient-handlers-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.main.ui.workspace.viewport.gradients :refer [radial-width-point point->gradient-width]]
   [cljs.test :as t :include-macros true]))

;; A 4:1 ellipse. The bug (#10069) only shows on non-square shapes.
(def ^:private wide-sr {:x 0 :y 0 :width 400 :height 100})
(def ^:private square-sr {:x 0 :y 0 :width 100 :height 100})

(defn- radial
  [start-x start-y end-x end-y width]
  {:type :radial
   :start-x start-x
   :start-y start-y
   :end-x end-x
   :end-y end-y
   :width width})

(defn- close?
  [a b]
  (< (mth/abs (- a b)) 0.0001))

(defn- point-close?
  [p1 p2]
  (and (close? (:x p1) (:x p2))
       (close? (:y p1) (:y p2))))

(t/deftest test-radial-width-point
  (t/testing "default vertical gradient is not affected"
    ;; No regression allowed here: the handler already sits at the correct
    ;; place when the gradient vector is axis aligned.
    (let [gradient (radial 0.5 0.5 0.5 1.0 1.0)]
      (t/is (point-close? (gpt/point 400 50)
                          (radial-width-point wide-sr gradient)))))

  (t/testing "rotated gradient keeps the handler inside the shape"
    ;; The gradient is defined in objectBoundingBox units, so the half-width
    ;; of a 90 degrees rotated gradient must be half the shape height (50),
    ;; not half the shape width scaled again by the aspect ratio (800).
    (let [gradient (radial 0.5 0.5 1.0 0.5 1.0)]
      (t/is (point-close? (gpt/point 200 0)
                          (radial-width-point wide-sr gradient)))))

  (t/testing "oblique gradient is anisotropically scaled"
    (let [gradient (radial 0.5 0.5 1.0 1.0 1.0)]
      (t/is (point-close? (gpt/point 400 0)
                          (radial-width-point wide-sr gradient)))))

  (t/testing "gradient width factor scales the handler distance"
    (let [gradient (radial 0.5 0.5 1.0 0.5 0.5)]
      (t/is (point-close? (gpt/point 200 25)
                          (radial-width-point wide-sr gradient)))))

  (t/testing "square shapes are isotropic"
    (let [gradient (radial 0.5 0.5 1.0 0.5 1.0)]
      (t/is (point-close? (gpt/point 50 0)
                          (radial-width-point square-sr gradient)))))

  (t/testing "translated selrect"
    (let [gradient (radial 0.5 0.5 1.0 0.5 1.0)]
      (t/is (point-close? (gpt/point 210 15)
                          (radial-width-point (assoc wide-sr :x 10 :y 15) gradient))))))

(t/deftest test-point->gradient-width
  (t/testing "is the inverse of radial-width-point"
    (doseq [gradient [(radial 0.5 0.5 0.5 1.0 1.0)
                      (radial 0.5 0.5 1.0 0.5 1.0)
                      (radial 0.5 0.5 1.0 1.0 0.5)
                      (radial 0.2 0.3 0.9 0.7 2.0)]
            selrect  [wide-sr square-sr]]
      (t/is (close? (:width gradient)
                    (point->gradient-width selrect gradient
                                           (radial-width-point selrect gradient))))))

  (t/testing "handler on the start point means zero width"
    (let [gradient (radial 0.5 0.5 1.0 0.5 1.0)]
      (t/is (close? 0 (point->gradient-width wide-sr gradient (gpt/point 200 50))))))

  (t/testing "degenerate gradient does not produce a usable width"
    (let [gradient (radial 0.5 0.5 0.5 0.5 1.0)]
      (t/is (not (js/Number.isFinite
                  (point->gradient-width wide-sr gradient (gpt/point 200 0))))))))
