(ns uxbox.test-util-geom
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.matrix :as gmt]))

(t/deftest point-constructors-test
  (let [p (gpt/point 1 2)]
    (t/is (= (:x p) 1))
    (t/is (= (:y p) 2)))

  (let [p (gpt/point 1)]
    (t/is (= (:x p) 1))
    (t/is (= (:y p) 1)))

  (let [p (gpt/point)]
    (t/is (= (:x p) 0))
    (t/is (= (:y p) 0))))

;; (t/deftest point-rotate-test
;;   (let [p1 (gpt/point 10 0)
;;         p2 (gpt/rotate p1 90)]
;;     (t/is (= (:x p2) 0))
;;     (t/is (= (:y p2) 10))))

(t/deftest point-add-test
  (let [p1 (gpt/point 1 1)
        p2 (gpt/point 2 2)
        p3 (gpt/add p1 p2)]
    (t/is (= (:x p3) 3))
    (t/is (= (:y p3) 3))))

(t/deftest point-subtract-test
  (let [p1 (gpt/point 3 3)
        p2 (gpt/point 2 2)
        p3 (gpt/subtract p1 p2)]
    (t/is (= (:x p3) 1))
    (t/is (= (:y p3) 1))))

(t/deftest point-distance-test
  (let [p1 (gpt/point 0 0)
        p2 (gpt/point 10 0)
        d (gpt/distance p1 p2)]
    (t/is (number? d))
    (t/is (= d 10))))

(t/deftest point-length-test
  (let [p1 (gpt/point 10 0)
        ln (gpt/length p1)]
    (t/is (number? ln))
    (t/is (= ln 10))))

(t/deftest point-angle-test
  (let [p1 (gpt/point 0 10)
        angle (gpt/angle p1)]
    (t/is (number? angle))
    (t/is (= angle 90)))
  (let [p1 (gpt/point 0 10)
        p2 (gpt/point 10 10)
        angle (gpt/angle-with-other p1 p2)]
    (t/is (number? angle))
    (t/is (= angle 45))))

(t/deftest matrix-constructors-test
  (let [m (gmt/matrix)]
    (t/is (= (str m) "matrix(1,0,0,1,0,0)")))
  (let [m (gmt/matrix 1 1 1 2 2 2)]
    (t/is (= (str m) "matrix(1,1,1,2,2,2)"))))

(t/deftest matrix-translate-test
  (let [m (-> (gmt/matrix)
              (gmt/translate (gpt/point 2 10)))]
    (t/is (str m) "matrix(1,0,0,1,2,10)")))

(t/deftest matrix-scale-test
  (let [m (-> (gmt/matrix)
              (gmt/scale (gpt/point 2)))]
    (t/is (str m) "matrix(2,0,0,2,0,0)")))

(t/deftest matrix-rotate-test
  (let [m (-> (gmt/matrix)
              (gmt/rotate 10))]
    (t/is (str m) "matrix(0.984807753012208,0.17364817766693033,-0.17364817766693033,0.984807753012208,0,0)")))
