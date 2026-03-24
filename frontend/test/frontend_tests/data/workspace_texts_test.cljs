;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.workspace-texts-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.types.shape :as cts]
   [app.main.data.workspace.texts :as dwt]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-text-shape
  "Build a fully initialised text shape at the given position."
  [& {:keys [x y width height position-data]
      :or   {x 10 y 20 width 100 height 50}}]
  (cond-> (cts/setup-shape {:type   :text
                            :x      x
                            :y      y
                            :width  width
                            :height height})
    (some? position-data)
    (assoc :position-data position-data)))

(defn- make-degenerate-text-shape
  "Simulate a text shape decoded from the server via map->Rect (which bypasses
  make-rect's 0.01 minimum enforcement), giving it a zero-width / zero-height
  selrect.  This is the exact condition that triggered the original crash:
  change-dimensions-modifiers divided by sr-width (== 0), producing an Infinity
  scale factor that propagated through the transform pipeline until
  calculate-selrect / center->rect returned nil, and then gpt/point threw
  'invalid arguments (on pointer constructor)'."
  [& {:keys [x y width height]
      :or   {x 10 y 20 width 0 height 0}}]
  (-> (make-text-shape :x x :y y :width 100 :height 50)
      ;; Bypass make-rect by constructing the Rect record directly, the same
      ;; way decode-rect does during JSON deserialization from the backend.
      (assoc :selrect (grc/map->Rect {:x x :y y
                                      :width  width :height  height
                                      :x1 x :y1 y
                                      :x2 (+ x width) :y2 (+ y height)}))))

(defn- sample-position-data
  "Return a minimal position-data vector with the supplied coords."
  [x y]
  [{:x x :y y :width 80 :height 16 :fills [] :text "hello"}])

;; ---------------------------------------------------------------------------
;; Tests: nil / no-op guard
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-nil-modifier-returns-shape-unchanged
  (t/testing "nil text-modifier returns the original shape untouched"
    (let [shape  (make-text-shape)
          result (dwt/apply-text-modifier shape nil)]
      (t/is (= shape result)))))

(t/deftest apply-text-modifier-empty-map-no-keys-returns-shape-unchanged
  (t/testing "modifier with no recognised keys leaves shape unchanged"
    (let [shape    (make-text-shape)
          modifier {}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (:selrect shape) (:selrect result)))
      (t/is (= (:width result) (:width shape)))
      (t/is (= (:height result) (:height shape))))))

;; ---------------------------------------------------------------------------
;; Tests: width modifier
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-width-changes-shape-width
  (t/testing "width modifier resizes the shape width"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 200.0 (-> result :selrect :width))))))

(t/deftest apply-text-modifier-width-nil-skips-width-change
  (t/testing "nil :width in modifier does not alter the width"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width nil}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (-> shape :selrect :width) (-> result :selrect :width))))))

;; ---------------------------------------------------------------------------
;; Tests: height modifier
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-height-changes-shape-height
  (t/testing "height modifier resizes the shape height"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:height 120}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 120.0 (-> result :selrect :height))))))

(t/deftest apply-text-modifier-height-nil-skips-height-change
  (t/testing "nil :height in modifier does not alter the height"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:height nil}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (-> shape :selrect :height) (-> result :selrect :height))))))

;; ---------------------------------------------------------------------------
;; Tests: width + height together
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-width-and-height-both-applied
  (t/testing "both width and height are applied simultaneously"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 300 :height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 300.0 (-> result :selrect :width)))
      (t/is (= 80.0  (-> result :selrect :height))))))

;; ---------------------------------------------------------------------------
;; Tests: position-data modifier
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-position-data-is-set-on-shape
  (t/testing "position-data modifier replaces the position-data on shape"
    (let [pd       (sample-position-data 5 10)
          shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:position-data pd}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? (:position-data result))))))

(t/deftest apply-text-modifier-position-data-nil-leaves-position-data-unchanged
  (t/testing "nil :position-data in modifier does not alter position-data"
    (let [pd       (sample-position-data 5 10)
          shape    (-> (make-text-shape :x 0 :y 0 :width 100 :height 50)
                       (assoc :position-data pd))
          modifier {:position-data nil}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= pd (:position-data result))))))

;; ---------------------------------------------------------------------------
;; Tests: position-data is translated by delta when shape moves
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-position-data-translated-on-resize
  (t/testing "position-data x/y is adjusted by the delta of the selrect origin"
    (let [pd       (sample-position-data 10 20)
          shape    (-> (make-text-shape :x 0 :y 0 :width 100 :height 50)
                       (assoc :position-data pd))
          ;; Only set position-data; no resize so no origin shift expected
          modifier {:position-data pd}
          result   (dwt/apply-text-modifier shape modifier)]
      ;; Delta should be zero (no dimension change), so coords stay the same
      (t/is (= 10.0 (-> result :position-data first :x)))
      (t/is (= 20.0 (-> result :position-data first :y))))))

(t/deftest apply-text-modifier-position-data-not-translated-when-nil
  (t/testing "nil position-data on result after modifier is left as nil"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      ;; shape had no position-data; modifier doesn't set one — stays nil
      (t/is (nil? (:position-data result))))))

;; ---------------------------------------------------------------------------
;; Tests: degenerate selrect (zero width or height decoded from the server)
;;
;; Root cause of the original crash:
;;   change-dimensions-modifiers divided by (:width selrect) or (:height selrect)
;;   which is 0 when the shape was decoded via map->Rect (bypassing make-rect's
;;   0.01 minimum), producing Infinity → transform pipeline returned nil selrect
;;   → gpt/point threw "invalid arguments (on pointer constructor)".
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-zero-width-selrect-does-not-throw
  (t/testing "width modifier on a shape with zero selrect width does not throw"
    ;; Simulates a shape received from the server whose selrect has width=0
    ;; (map->Rect bypasses the 0.01 floor of make-rect).
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-zero-height-selrect-does-not-throw
  (t/testing "height modifier on a shape with zero selrect height does not throw"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 100 :height 0)
          modifier {:height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-zero-width-and-height-selrect-does-not-throw
  (t/testing "both modifiers on a fully-degenerate selrect do not throw"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          modifier {:width 150 :height 60}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-zero-width-selrect-result-has-correct-width
  (t/testing "applying width modifier to a zero-width shape yields the requested width"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 200.0 (-> result :selrect :width))))))

(t/deftest apply-text-modifier-zero-height-selrect-result-has-correct-height
  (t/testing "applying height modifier to a zero-height shape yields the requested height"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 100 :height 0)
          modifier {:height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 80.0 (-> result :selrect :height))))))

(t/deftest apply-text-modifier-nil-modifier-on-degenerate-shape-returns-unchanged
  (t/testing "nil modifier on a zero-selrect shape returns the same shape"
    (let [shape  (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          result (dwt/apply-text-modifier shape nil)]
      (t/is (identical? shape result)))))

;; ---------------------------------------------------------------------------
;; Tests: shape origin is preserved when there is no dimension change
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-selrect-origin-preserved-without-resize
  (t/testing "selrect x/y origin does not shift when no dimension changes"
    (let [shape    (make-text-shape :x 30 :y 40 :width 100 :height 50)
          modifier {:position-data (sample-position-data 30 40)}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (-> shape :selrect :x) (-> result :selrect :x)))
      (t/is (= (-> shape :selrect :y) (-> result :selrect :y))))))

;; ---------------------------------------------------------------------------
;; Tests: returned shape is a proper map-like value
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-returns-shape-with-required-keys
  (t/testing "result always contains the core shape keys"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 200 :height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? (:id result)))
      (t/is (some? (:type result)))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-nil-modifier-returns-same-identity
  (t/testing "nil modifier returns the exact same shape object (identity)"
    (let [shape (make-text-shape)]
      (t/is (identical? shape (dwt/apply-text-modifier shape nil))))))
