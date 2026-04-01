;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-strokes-test
  (:require
   [app.common.geom.shapes.strokes :as gss]
   [clojure.test :as t]))

(t/deftest update-stroke-width-test
  (t/testing "Scale a stroke by 2"
    (let [stroke {:stroke-width 4 :stroke-color "#000"}
          scaled (gss/update-stroke-width stroke 2)]
      (t/is (= 8 (:stroke-width scaled)))
      (t/is (= "#000" (:stroke-color scaled)))))

  (t/testing "Scale by 1 preserves width"
    (let [stroke {:stroke-width 4}
          scaled (gss/update-stroke-width stroke 1)]
      (t/is (= 4 (:stroke-width scaled)))))

  (t/testing "Scale by 0 zeroes width"
    (let [stroke {:stroke-width 4}
          scaled (gss/update-stroke-width stroke 0)]
      (t/is (= 0 (:stroke-width scaled))))))

(t/deftest update-strokes-width-test
  (t/testing "Scale all strokes on a shape"
    (let [shape  {:strokes [{:stroke-width 2 :stroke-color "#aaa"}
                            {:stroke-width 5 :stroke-color "#bbb"}]}
          scaled (gss/update-strokes-width shape 3)
          s1     (first (:strokes scaled))
          s2     (second (:strokes scaled))]
      (t/is (= 6 (:stroke-width s1)))
      (t/is (= "#aaa" (:stroke-color s1)))
      (t/is (= 15 (:stroke-width s2)))
      (t/is (= "#bbb" (:stroke-color s2)))))

  (t/testing "Empty strokes stays empty"
    (let [shape  {:strokes []}
          scaled (gss/update-strokes-width shape 2)]
      (t/is (empty? (:strokes scaled)))))

  (t/testing "Shape with no :strokes key returns empty vector (mapv on nil)"
    (let [scaled (gss/update-strokes-width {} 2)]
      (t/is (= [] (:strokes scaled))))))
