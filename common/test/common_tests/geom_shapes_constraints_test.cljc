;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.geom-shapes-constraints-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.constraints :as gsc]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

;; ---- constraint-modifier :default ----

(t/deftest constraint-modifier-default-returns-empty-modifiers
  (t/testing ":default method accepts 6 args and returns empty modifiers"
    ;; :scale and any other type with no method of its own add nothing; the
    ;; caller adds the result to the child's modifiers (`ctm/add-modifiers`)
    (let [result (gsc/constraint-modifier :unknown-constraint-type
                                          :x nil nil nil nil)]
      (t/is (ctm/empty? result))))

  (t/testing ":default method can be added to modifiers"
    (let [result (gsc/constraint-modifier :scale :y nil nil nil nil)]
      (t/is (ctm/empty? (ctm/add-modifiers (ctm/empty) result))))))

;; ---- constraint-modifier :start ----

(defn- rect-points
  [x y w h]
  [(gpt/point x y) (gpt/point (+ x w) y) (gpt/point (+ x w) (+ y h)) (gpt/point x (+ y h))])

(defn- move-vector
  [modifiers]
  (reduce gpt/add (gpt/point 0 0)
          (keep #(when (= :move (:type %)) (:vector %)) (:geometry-child modifiers))))

(t/deftest start-constraint-moves-a-child-onto-the-start-of-its-squeezed-parent
  (t/testing "a child at the end of its parent keeps its start offset when the
              parent is squeezed to zero and moved onto it"
    (let [child  (rect-points 100 0 100 100)
          before (rect-points 0 0 100 100)
          after  (rect-points 100 0 0.01 100)
          result (gsc/constraint-modifier :start :x child before child after)]
      (t/is (gpt/close? (gpt/point 100 0) (move-vector result)))))

  (t/testing "a child the moved parent passes over keeps its start offset"
    (let [child  (rect-points 50 0 100 100)
          before (rect-points 0 0 100 100)
          after  (rect-points 100 0 0.01 100)
          result (gsc/constraint-modifier :start :x child before child after)]
      (t/is (gpt/close? (gpt/point 100 0) (move-vector result))))))
