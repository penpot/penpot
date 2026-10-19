;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-image-crop-test
  (:require
   [app.common.geom.image-crop :as gic]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [clojure.test :as t]))

(defn- close-vals?
  [a b]
  (every? true? (map mth/close? a b)))

(defn- picture-box
  "Picture position and size relative to the original window start, for a
   window at `offset` with `size` and the given image transform."
  [{:keys [x y width height]} [dx dy] [w h]]
  [(+ dx (* x w)) (+ dy (* y h)) (* width w) (* height h)])

(t/deftest crop-axis-test
  (t/testing "shrinks the window towards the fixed edge"
    ;; Top handler: the bottom edge (300) stays fixed.
    (t/is (close-vals? [60 240] (gic/crop-axis 300 300 0.8))))

  (t/testing "grows the window away from the fixed edge"
    (t/is (close-vals? [-60 360] (gic/crop-axis 300 300 1.2))))

  (t/testing "crossing the fixed edge moves the window to the other side"
    ;; Top handler dragged 100px below the bottom edge.
    (t/is (close-vals? [300 100] (gic/crop-axis 300 300 (/ -1 3))))
    ;; Bottom handler dragged 150px above the top edge.
    (t/is (close-vals? [-150 150] (gic/crop-axis 300 0 -0.5))))

  (t/testing "resize from the center keeps the center when crossing"
    (t/is (close-vals? [75 150] (gic/crop-axis 300 150 -0.5)))))

(t/deftest update-image-transform-test
  (let [width  400
        height 300
        tf     {:x 0 :y -0.2 :width 1 :height 1.4}]

    (t/testing "the picture keeps its position and size"
      (doseq [[dx dy w' h'] [[0 60 400 240]
                             [0 300 400 100]
                             [0 -150 400 150]
                             [-50 75 500 150]]]
        (let [tf' (gic/update-image-transform tf width height dx dy w' h')]
          (t/is (close-vals? (picture-box tf [0 0] [width height])
                             (picture-box tf' [dx dy] [w' h']))
                (str "window " [dx dy w' h'])))))

    (t/testing "a missing transform means the picture fills the window"
      (t/is (close-vals? [0 -0.25 1 1.25]
                         ((juxt :x :y :width :height)
                          (gic/update-image-transform nil 400 300 0 60 400 240)))))

    (t/testing "moving the window back restores the original transform"
      (let [[dy h'] (gic/crop-axis height height (/ -1 3))
            crossed (gic/update-image-transform tf width height 0 dy width h')
            [dy2 h2] (gic/crop-axis h' 0 (/ height h' -1))
            back    (gic/update-image-transform crossed width h' 0 dy2 width h2)]
        (t/is (close-vals? [(- dy) height] [dy2 h2]) "window back at its original place")
        (t/is (close-vals? ((juxt :x :y :width :height) tf)
                           ((juxt :x :y :width :height) back)))))))

(t/deftest preview-image-matrix-test
  (let [width  400
        height 300
        tf     {:x 0 :y -0.2 :width 1 :height 1.4}]

    (t/testing "the picture keeps its place once the window is resized"
      (doseq [[dx dy w' h'] [[0 60 400 240]
                             [0 300 400 100]
                             [-50 75 500 150]]]
        (let [tf'    (gic/update-image-transform tf width height dx dy w' h')
              matrix (gic/preview-image-matrix tf tf' width height)
              ;; Resize the preview applies to the whole shape: the old
              ;; window goes to the new one.
              window (-> (gmt/matrix)
                         (gmt/translate (gpt/point dx dy))
                         (gmt/scale (gpt/point (/ w' width) (/ h' height))))
              [x y w h] (picture-box tf [0 0] [width height])
              p1     (gpt/transform (gpt/point x y) (gmt/multiply window matrix))
              p2     (gpt/transform (gpt/point (+ x w) (+ y h)) (gmt/multiply window matrix))]
          (t/is (close-vals? [x y (+ x w) (+ y h)] [(:x p1) (:y p1) (:x p2) (:y p2)])
                (str "window " [dx dy w' h'])))))

    (t/testing "no change gives the identity"
      (t/is (gmt/close? (gmt/matrix) (gic/preview-image-matrix tf tf width height)))
      (t/is (gmt/close? (gmt/matrix) (gic/preview-image-matrix nil nil width height))))))
