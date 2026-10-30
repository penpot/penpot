;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-shapes-constraints-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.constraints :as gsc]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; ---- constraint-modifier :default ----

(t/deftest constraint-modifier-default-returns-empty-modifiers
  (t/is (= (ctm/empty)
           (gsc/constraint-modifier :unknown-constraint-type :x nil nil nil nil))))

;; ---- follow-parent-modifiers / resize-with-parent ----

(def ^:private parent-id (uuid/next))

(defn- make-parent
  [x y width height]
  (cts/setup-shape {:id parent-id :type :frame :x x :y y :width width :height height}))

(defn- make-child
  [x y width height constraints-h]
  (cts/setup-shape {:type :rect :x x :y y :width width :height height
                    :parent-id parent-id :frame-id parent-id
                    :constraints-h constraints-h :constraints-v :top}))

(defn- follow
  [child parent-before parent-after]
  (gsh/transform-shape child (gsc/follow-parent-modifiers child parent-before parent-after)))

(t/deftest follow-parent-modifiers-right-constraint-keeps-distance-to-right
  (let [child (follow (make-child 80 10 20 20 :right)
                      (make-parent 0 0 100 100)
                      (make-parent 0 0 200 100))]
    (t/is (mth/close? 180 (-> child :selrect :x)))
    (t/is (mth/close? 20 (-> child :selrect :width)))))

(t/deftest follow-parent-modifiers-scale-constraint-scales
  (let [child (follow (make-child 50 10 20 20 :scale)
                      (make-parent 0 0 100 100)
                      (make-parent 0 0 200 100))]
    (t/is (mth/close? 100 (-> child :selrect :x)))
    (t/is (mth/close? 40 (-> child :selrect :width)))))

(t/deftest follow-parent-modifiers-move-only-moves
  (let [child     (make-child 50 10 20 20 :scale)
        modifiers (gsc/follow-parent-modifiers child
                                               (make-parent 0 0 100 100)
                                               (make-parent 30 5 100 100))]
    (t/is (ctm/only-move? modifiers))
    (t/is (gpt/close? (gpt/point 80 15)
                      (gpt/point (:selrect (gsh/transform-shape child modifiers)))))))

(t/deftest follow-parent-modifiers-no-change-is-empty
  (t/is (ctm/empty? (gsc/follow-parent-modifiers (make-child 50 10 20 20 :left)
                                                 (make-parent 0 0 100 100)
                                                 (make-parent 0 0 100 100)))))

(t/deftest resize-with-parent-keeps-top-left
  (let [child (gsc/resize-with-parent (make-child 50 10 20 20 :scale)
                                      (make-parent 0 0 100 100)
                                      (make-parent 40 0 200 100))]
    (t/is (mth/close? 50 (-> child :selrect :x)))
    (t/is (mth/close? 10 (-> child :selrect :y)))
    (t/is (mth/close? 40 (-> child :selrect :width)))))

(t/deftest resize-with-parent-left-constraint-keeps-size
  (let [child (make-child 50 10 20 20 :left)]
    (t/is (= (:selrect child)
             (:selrect (gsc/resize-with-parent child
                                               (make-parent 0 0 100 100)
                                               (make-parent 0 0 200 100)))))))
