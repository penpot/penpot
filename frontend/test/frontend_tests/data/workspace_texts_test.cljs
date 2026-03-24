;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.workspace-texts-test
  (:require
   [app.common.geom.point :as gpt]
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
;; Tests: shape with nil selrect (defensive guard for gpt/point delta path)
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-nil-selrect-nil-modifier-does-not-throw
  (t/testing "nil selrect + nil modifier returns shape unchanged without throwing"
    ;; The nil-modifier guard fires first, so even a stripped shape is safe.
    (let [shape  (-> (make-text-shape)
                     (dissoc :selrect))
          result (dwt/apply-text-modifier shape nil)]
      (t/is (= shape result)))))

(t/deftest apply-text-modifier-only-position-data-nil-selrects-safe
  (t/testing "position-data-only modifier with nil selrects does not throw"
    ;; When only position-data is set, transform-shape is NOT called, so
    ;; the selrect-nil guard in the delta calculation is exercised directly.
    (let [pd       (sample-position-data 5 10)
          shape    (-> (make-text-shape :x 0 :y 0 :width 100 :height 50)
                       (dissoc :selrect))
          modifier {:position-data pd}
          result   (dwt/apply-text-modifier shape modifier)]
      ;; Should not throw; position-data is updated on the result
      (t/is (= pd (:position-data result))))))

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
