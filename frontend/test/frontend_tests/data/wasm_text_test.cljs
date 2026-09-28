;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.wasm-text-test
  "Growth anchor of auto-grow wasm text shapes (see `resize-wasm-text-modifiers`).
  Tests stub the wasm bridge and assert on the shape the modifiers produce."
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.main.data.workspace.wasm-text :as dwwt]
   [cljs.test :as t :include-macros true]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-content
  "Text content whose paragraphs carry the given `:text-direction` values."
  [& directions]
  {:type "root"
   :children [{:type "paragraph-set"
               :children (vec (for [direction directions]
                                {:type "paragraph"
                                 :text-direction direction
                                 :children [{:text "hello"}]}))}]})

(defn- make-text-shape
  [& {:keys [x y width height grow-type rotation]
      :or   {x 100 y 50 width 60 height 20 grow-type :auto-width}}]
  (let [shape (-> (cts/setup-shape {:type   :text
                                    :x      x
                                    :y      y
                                    :width  width
                                    :height height})
                  (assoc :grow-type grow-type))]
    (if (some? rotation)
      (gsh/transform-shape
       shape
       (ctm/rotation-modifiers shape (gsh/shape->center shape) rotation))
      shape)))

(defn- resized
  "Shape that results from `resize-wasm-text-modifiers` when the renderer
  measures `new-size`."
  [shape content new-size]
  ;; The stub needs the real fn's arities: a variadic `fn` has no `arity$2`
  ;; dispatch, so the 2-arity call site blows up.
  (with-redefs [dwwt/get-wasm-text-new-size (fn ([_] new-size) ([_ _] new-size))]
    (let [modifiers (dwwt/resize-wasm-text-modifiers shape content)]
      (gsh/transform-shape shape (get-in modifiers [(:id shape) :modifiers])))))

(defn- close? [a b]
  (< (abs (- a b)) 0.01))

;; ---------------------------------------------------------------------------
;; Growth anchor
;; ---------------------------------------------------------------------------

(t/deftest rtl-auto-width-keeps-its-right-edge-when-growing
  (t/testing "an rtl auto-width shape extends leftward: the right and top edges
              stay put and x moves left"
    (let [shape   (make-text-shape)
          content (make-content "rtl")
          before  (:selrect shape)
          after   (:selrect (resized shape content {:width 120 :height 20}))]
      (t/is (close? (+ (:x after) (:width after))
                    (+ (:x before) (:width before)))
            "right edge preserved")
      (t/is (close? (:y after) (:y before)) "top edge preserved")
      (t/is (close? (:x after) 40) "x moved left by the growth")
      (t/is (close? (:width after) 120)))))

(t/deftest rtl-auto-width-keeps-its-right-edge-when-shrinking
  (t/testing "deleting text shrinks the box from the left, right edge preserved"
    (let [shape   (make-text-shape)
          content (make-content "rtl")
          before  (:selrect shape)
          after   (:selrect (resized shape content {:width 30 :height 20}))]
      (t/is (close? (+ (:x after) (:width after))
                    (+ (:x before) (:width before)))
            "right edge preserved")
      (t/is (close? (:x after) 130) "x moved right as the box narrowed"))))

(t/deftest ltr-auto-width-keeps-its-left-edge
  (t/testing "ltr content is untouched: the left edge stays anchored"
    (let [shape   (make-text-shape)
          content (make-content "ltr")
          after   (:selrect (resized shape content {:width 120 :height 20}))]
      (t/is (close? (:x after) 100) "left edge preserved")
      (t/is (close? (:width after) 120)))))

(t/deftest mixed-direction-auto-width-keeps-its-left-edge
  (t/testing "a box with one rtl and one ltr paragraph keeps the previous
              left-anchored growth"
    (let [shape   (make-text-shape)
          content (make-content "rtl" "ltr")
          after   (:selrect (resized shape content {:width 120 :height 20}))]
      (t/is (close? (:x after) 100) "left edge preserved"))))

(t/deftest rtl-auto-height-keeps-its-left-edge
  (t/testing "auto-height only ever changes height, so the anchor is irrelevant
              and x must not move"
    (let [shape   (make-text-shape :grow-type :auto-height)
          content (make-content "rtl")
          after   (:selrect (resized shape content {:width 60 :height 80}))]
      (t/is (close? (:x after) 100) "x preserved")
      (t/is (close? (:width after) 60) "width preserved")
      (t/is (close? (:height after) 80) "height grew"))))

(t/deftest rotated-rtl-auto-width-grows-along-its-own-axis
  (t/testing "a rotated rtl shape keeps its own top-right corner, not the
              axis-aligned one"
    (let [shape   (make-text-shape :rotation 30)
          content (make-content "rtl")
          before  (:points shape)
          after   (:points (resized shape content {:width 120 :height 20}))]
      ;; points are [top-left top-right bottom-right bottom-left]
      (t/is (close? (:x (second after)) (:x (second before)))
            "top-right x preserved")
      (t/is (close? (:y (second after)) (:y (second before)))
            "top-right y preserved"))))

(t/deftest no-modifiers-when-the-renderer-has-no-size
  (t/testing "a nil measurement (shape absent from wasm state) skips the resize"
    (let [shape (make-text-shape)]
      (with-redefs [dwwt/get-wasm-text-new-size (fn ([_] nil) ([_ _] nil))]
        (t/is (nil? (dwwt/resize-wasm-text-modifiers shape (make-content "rtl"))))))))
