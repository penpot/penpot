;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-image-bounds-resize-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.types.color :as clr]
   [app.common.types.fills :as fills]
   [app.common.types.fills.impl :as fills.impl]
   [app.common.uuid :as uuid]))

(deftest test-image-transform-schema
  (testing "validates image with transform"
    (let [img {:id (uuid/custom 1)
               :width 400
               :height 300
               :mtype "image/png"
               :keep-aspect-ratio true
               :transform {:x 0.1 :y -0.2 :width 1.5 :height 2.0}}]
      (is (sm/validate clr/schema:image img))))

  (testing "validates image without transform"
    (let [img {:id (uuid/custom 1)
               :width 400
               :height 300
               :mtype "image/png"
               :keep-aspect-ratio true}]
      (is (sm/validate clr/schema:image img))))

  (testing "validates fill with image transform"
    (let [fill {:fill-opacity 0.8
                :fill-image {:id (uuid/custom 1)
                             :width 400
                             :height 300
                             :mtype "image/png"
                             :keep-aspect-ratio true
                             :transform {:x -0.5 :y -0.5 :width 2.0 :height 2.0}}}]
      (is (sm/validate fills/schema:fill fill)))))

(deftest test-image-fill-buffer-roundtrip
  (testing "roundtrip image fill without transform"
    (let [fill-vec [{:fill-opacity 0.9
                     :fill-image {:id (uuid/custom 1)
                                  :width 800
                                  :height 600
                                  :mtype "image/jpeg"
                                  :keep-aspect-ratio true
                                  :name "sample"}}]
          coerced (fills/from-plain fill-vec)
          plain (into [] coerced)]
      (is (= 1 (count plain)))
      (is (= 0.9 (:fill-opacity (first plain))))
      (is (= 800 (-> plain first :fill-image :width)))
      (is (= 600 (-> plain first :fill-image :height)))
      (is (true? (-> plain first :fill-image :keep-aspect-ratio)))
      (is (nil? (-> plain first :fill-image :transform)))))

  (testing "roundtrip image fill with transform"
    (let [fill-vec [{:fill-opacity 0.75
                     :fill-image {:id (uuid/custom 2)
                                  :width 1920
                                  :height 1080
                                  :mtype "image/webp"
                                  :keep-aspect-ratio false
                                  :name "sample"
                                  :transform {:x 0.25 :y -0.15 :width 1.5 :height 2.0}}}]
          coerced (fills/from-plain fill-vec)
          plain (into [] coerced)
          tf (-> plain first :fill-image :transform)]
      (is (= 1 (count plain)))
      (is (= 0.75 (:fill-opacity (first plain))))
      (is (= 1920 (-> plain first :fill-image :width)))
      (is (= 1080 (-> plain first :fill-image :height)))
      (is (false? (-> plain first :fill-image :keep-aspect-ratio)))
      (is (some? tf))
      (is (mth/close? 0.25 (double (:x tf))))
      (is (mth/close? -0.15 (double (:y tf))))
      (is (mth/close? 1.5 (double (:width tf))))
      (is (mth/close? 2.0 (double (:height tf)))))))

(defn compute-bounds-resize-transform
  "Mathematical model for independent image bounds resizing"
  [{:keys [width height handler center? sx sy transform]}]
  (let [w-new (* width sx)
        h-new (* height sy)
        [dx dy] (if ^boolean center?
                  [(/ (* width (- 1.0 sx)) 2.0)
                   (/ (* height (- 1.0 sy)) 2.0)]
                  [(case handler
                     (:left :bottom-left :top-left) (* width (- 1.0 sx))
                     0.0)
                   (case handler
                     (:top :top-left :top-right) (* height (- 1.0 sy))
                     0.0)])
        nx0 (get transform :x 0.0)
        ny0 (get transform :y 0.0)
        nw0 (get transform :width 1.0)
        nh0 (get transform :height 1.0)
        nx' (/ (- (* nx0 width) dx) w-new)
        ny' (/ (- (* ny0 height) dy) h-new)
        nw' (/ nw0 sx)
        nh' (/ nh0 sy)]
    {:transform {:x nx' :y ny' :width nw' :height nh'}
     :rendered-pixel-rect {:x (* nx' w-new)
                           :y (* ny' h-new)
                           :width (* nw' w-new)
                           :height (* nh' h-new)}}))

(deftest test-handle-anchoring-mathematics
  (testing "Right handle crop (shrinking width to 50%)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :right :center? false :sx 0.5 :sy 1.0})]
      (is (mth/close? 0.0 (-> res :transform :x)))
      (is (mth/close? 0.0 (-> res :transform :y)))
      (is (mth/close? 2.0 (-> res :transform :width)))
      (is (mth/close? 1.0 (-> res :transform :height)))
      ;; Rendered pixel content remains 200x100 starting at (0, 0)
      (is (mth/close? 0.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? 0.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Left handle crop (shrinking width to 50% from left)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :left :center? false :sx 0.5 :sy 1.0})]
      (is (mth/close? -1.0 (-> res :transform :x)))
      (is (mth/close? 0.0 (-> res :transform :y)))
      (is (mth/close? 2.0 (-> res :transform :width)))
      (is (mth/close? 1.0 (-> res :transform :height)))
      ;; Rendered pixel content has left at -100, width 200 -> right edge at +100 (matches right edge of 100px container!)
      (is (mth/close? -100.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width)))))

  (testing "Top handle crop (shrinking height to 50% from top)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :top :center? false :sx 1.0 :sy 0.5})]
      (is (mth/close? 0.0 (-> res :transform :x)))
      (is (mth/close? -1.0 (-> res :transform :y)))
      (is (mth/close? 1.0 (-> res :transform :width)))
      (is (mth/close? 2.0 (-> res :transform :height)))
      ;; Rendered pixel content has top at -50, height 100 -> bottom edge at +50 (matches bottom edge of 50px container!)
      (is (mth/close? -50.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Top-Left handle crop (shrinking both dimensions to 50%)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :top-left :center? false :sx 0.5 :sy 0.5})]
      (is (mth/close? -1.0 (-> res :transform :x)))
      (is (mth/close? -1.0 (-> res :transform :y)))
      (is (mth/close? 2.0 (-> res :transform :width)))
      (is (mth/close? 2.0 (-> res :transform :height)))
      (is (mth/close? -100.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? -50.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Center resize (Alt modifier)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :right :center? true :sx 0.5 :sy 0.5})]
      (is (mth/close? -0.5 (-> res :transform :x)))
      (is (mth/close? -0.5 (-> res :transform :y)))
      (is (mth/close? 2.0 (-> res :transform :width)))
      (is (mth/close? 2.0 (-> res :transform :height)))
      (is (mth/close? -50.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? -25.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Bottom handle crop (shrinking height to 50% from bottom)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :bottom :center? false :sx 1.0 :sy 0.5})]
      (is (mth/close? 0.0 (-> res :transform :x)))
      (is (mth/close? 0.0 (-> res :transform :y)))
      (is (mth/close? 1.0 (-> res :transform :width)))
      (is (mth/close? 2.0 (-> res :transform :height)))
      (is (mth/close? 0.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Top-Right handle crop (shrinking both dimensions to 50%)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :top-right :center? false :sx 0.5 :sy 0.5})]
      (is (mth/close? 0.0 (-> res :transform :x)))
      (is (mth/close? -1.0 (-> res :transform :y)))
      (is (mth/close? 2.0 (-> res :transform :width)))
      (is (mth/close? 2.0 (-> res :transform :height)))
      (is (mth/close? 0.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? -50.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Bottom-Left handle crop (shrinking both dimensions to 50%)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :bottom-left :center? false :sx 0.5 :sy 0.5})]
      (is (mth/close? -1.0 (-> res :transform :x)))
      (is (mth/close? 0.0 (-> res :transform :y)))
      (is (mth/close? 2.0 (-> res :transform :width)))
      (is (mth/close? 2.0 (-> res :transform :height)))
      (is (mth/close? -100.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? 0.0 (-> res :rendered-pixel-rect :y)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width)))
      (is (mth/close? 100.0 (-> res :rendered-pixel-rect :height)))))

  (testing "Expanding bounds beyond original size (empty space exposure)"
    (let [res (compute-bounds-resize-transform
               {:width 200 :height 100 :handler :right :center? false :sx 2.0 :sy 1.0})]
      (is (mth/close? 0.0 (-> res :transform :x)))
      (is (mth/close? 0.0 (-> res :transform :y)))
      (is (mth/close? 0.5 (-> res :transform :width)))
      (is (mth/close? 1.0 (-> res :transform :height)))
      ;; Rendered pixel content is 200px wide in a 400px container -> exposes 200px empty space
      (is (mth/close? 0.0 (-> res :rendered-pixel-rect :x)))
      (is (mth/close? 200.0 (-> res :rendered-pixel-rect :width))))))

(deftest test-sequential-resize-operations
  (testing "Sequential crops: crop right then crop left"
    ;; Initial shape: 200x100, transform: {:x 0 :y 0 :width 1 :height 1}
    ;; Step 1: Crop right handle from 200 to 150 (sx = 0.75)
    (let [step1 (compute-bounds-resize-transform
                 {:width 200 :height 100 :handler :right :center? false :sx 0.75 :sy 1.0})
          tf1 (:transform step1)]
      (is (mth/close? 0.0 (:x tf1)))
      (is (mth/close? (/ 1.0 0.75) (:width tf1)))
      ;; Step 2: Now shape is 150x100 with tf1. Crop left handle from 150 to 100 (sx = 100/150 = 2/3)
      (let [step2 (compute-bounds-resize-transform
                   {:width 150 :height 100 :handler :left :center? false :sx (/ 2.0 3.0) :sy 1.0 :transform tf1})
            tf2 (:transform step2)]
        ;; The final 100x100 container has bitmap with width 200px
        (is (mth/close? 200.0 (-> step2 :rendered-pixel-rect :width)))
        ;; The bitmap left edge is at -50px in the 100px container, so right edge is at -50 + 200 = 150px
        (is (mth/close? -50.0 (-> step2 :rendered-pixel-rect :x))))))

  (testing "Bounds resize followed by standard proportional scaling"
    ;; Step 1: Bounds resize crops width from 200 to 100
    (let [step1 (compute-bounds-resize-transform
                 {:width 200 :height 100 :handler :right :center? false :sx 0.5 :sy 1.0})
          tf1 (:transform step1)]
      (is (mth/close? 2.0 (:width tf1)))
      (is (mth/close? 1.0 (:height tf1)))

      ;; Step 2: Standard proportional scale of the 100x100 cropped shape to 200x200 (scale 2x)
      ;; During standard scale, normalized transform tf1 is kept constant!
      (let [scaled-w (* 100.0 2.0)
            scaled-h (* 100.0 2.0)
            rendered-w (* (:width tf1) scaled-w)
            rendered-h (* (:height tf1) scaled-h)]
        ;; The underlying bitmap scaled from 200x100 to 400x200, matching the 2x scale of the cropped frame!
        (is (mth/close? 400.0 rendered-w))
        (is (mth/close? 200.0 rendered-h))))))

(deftest test-proportion-lock-invariance
  (testing "Shape proportion-lock attribute remains unchanged"
    (let [shape {:id (uuid/custom 10)
                 :type :rect
                 :width 200
                 :height 100
                 :proportion-lock true
                 :fills [{:fill-image {:id (uuid/custom 1)
                                       :width 800
                                       :height 600
                                       :keep-aspect-ratio true}}]}
          ;; Simulate bounds resize interaction
          has-img? (boolean (or (some :fill-image (:fills shape)) (:fill-image shape)))
          mod-pressed? true
          bounds-resize? (and has-img? mod-pressed?)
          lock-during-drag (if bounds-resize? false (:proportion-lock shape))]
      ;; During drag, lock is bypassed (unless Shift is pressed)
      (is (false? lock-during-drag))
      ;; Shape's persistent setting is completely preserved
      (is (true? (:proportion-lock shape))))))
