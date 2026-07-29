;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-rect-test
  (:require
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [clojure.test :as t]))

;; ---- update-rect :size ----

(t/deftest update-rect-size-sets-all-corners
  (t/testing ":size updates x1/y1 as well as x2/y2 from x/y/width/height"
    (let [r  (grc/make-rect 10 20 30 40)
          r' (grc/update-rect r :size)]
      ;; x1/y1 must mirror x/y
      (t/is (mth/close? (:x r) (:x1 r')))
      (t/is (mth/close? (:y r) (:y1 r')))
      ;; x2/y2 must be x+width / y+height
      (t/is (mth/close? (+ (:x r) (:width r)) (:x2 r')))
      (t/is (mth/close? (+ (:y r) (:height r)) (:y2 r')))))

  (t/testing ":size is consistent with :corners round-trip"
    ;; Applying :size then :corners should recover the original x/y/w/h
    (let [r    (grc/make-rect 5 15 100 50)
          r'   (-> r (grc/update-rect :size) (grc/update-rect :corners))]
      (t/is (mth/close? (:x r)      (:x r')))
      (t/is (mth/close? (:y r)      (:y r')))
      (t/is (mth/close? (:width r)  (:width r')))
      (t/is (mth/close? (:height r) (:height r')))))

  (t/testing ":size works for a rect at the origin"
    (let [r  (grc/make-rect 0 0 200 100)
          r' (grc/update-rect r :size)]
      (t/is (mth/close? 0   (:x1 r')))
      (t/is (mth/close? 0   (:y1 r')))
      (t/is (mth/close? 200 (:x2 r')))
      (t/is (mth/close? 100 (:y2 r'))))))

;; ---- corners->rect ----

(t/deftest corners->rect-normal-order
  (t/testing "p1 top-left, p2 bottom-right yields a valid rect"
    (let [r (grc/corners->rect 0 0 10 20)]
      (t/is (grc/rect? r))
      (t/is (mth/close? 0  (:x r)))
      (t/is (mth/close? 0  (:y r)))
      (t/is (mth/close? 10 (:width r)))
      (t/is (mth/close? 20 (:height r))))))

(t/deftest corners->rect-reversed-corners
  (t/testing "reversed x-coordinates still produce a positive-width rect"
    (let [r (grc/corners->rect 10 0 0 20)]
      (t/is (grc/rect? r))
      (t/is (mth/close? 0  (:x r)))
      (t/is (mth/close? 10 (:width r)))))

  (t/testing "reversed y-coordinates still produce a positive-height rect"
    (let [r (grc/corners->rect 0 20 10 0)]
      (t/is (grc/rect? r))
      (t/is (mth/close? 0  (:y r)))
      (t/is (mth/close? 20 (:height r)))))

  (t/testing "both axes reversed yield the same rect as normal order"
    (let [r-normal   (grc/corners->rect 0 0 10 20)
          r-reversed (grc/corners->rect 10 20 0 0)]
      (t/is (mth/close? (:x      r-normal) (:x      r-reversed)))
      (t/is (mth/close? (:y      r-normal) (:y      r-reversed)))
      (t/is (mth/close? (:width  r-normal) (:width  r-reversed)))
      (t/is (mth/close? (:height r-normal) (:height r-reversed))))))

(t/deftest corners->rect-from-points
  (t/testing "two-arity overload taking point maps works identically"
    (let [p1 {:x 5 :y 10}
          p2 {:x 15 :y 30}
          r  (grc/corners->rect p1 p2)]
      (t/is (grc/rect? r))
      (t/is (mth/close? 5  (:x r)))
      (t/is (mth/close? 10 (:y r)))
      (t/is (mth/close? 10 (:width r)))
      (t/is (mth/close? 20 (:height r)))))

  (t/testing "two-arity overload with reversed points"
    (let [p1 {:x 15 :y 30}
          p2 {:x 5  :y 10}
          r  (grc/corners->rect p1 p2)]
      (t/is (grc/rect? r))
      (t/is (mth/close? 5  (:x r)))
      (t/is (mth/close? 10 (:y r)))
      (t/is (mth/close? 10 (:width r)))
      (t/is (mth/close? 20 (:height r))))))
