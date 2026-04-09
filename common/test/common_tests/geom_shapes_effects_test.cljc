;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.geom-shapes-effects-test
  (:require
   [app.common.geom.shapes.effects :as gef]
   [clojure.test :as t]))

(t/deftest update-shadow-scale-test
  (t/testing "Scale a shadow by 2"
    (let [shadow {:offset-x 10 :offset-y 20 :spread 5 :blur 15}
          scaled (gef/update-shadow-scale shadow 2)]
      (t/is (= 20 (:offset-x scaled)))
      (t/is (= 40 (:offset-y scaled)))
      (t/is (= 10 (:spread scaled)))
      (t/is (= 30 (:blur scaled)))))

  (t/testing "Scale by 1 preserves values"
    (let [shadow {:offset-x 10 :offset-y 20 :spread 5 :blur 15}
          scaled (gef/update-shadow-scale shadow 1)]
      (t/is (= 10 (:offset-x scaled)))
      (t/is (= 20 (:offset-y scaled)))
      (t/is (= 5 (:spread scaled)))
      (t/is (= 15 (:blur scaled)))))

  (t/testing "Scale by 0 zeroes everything"
    (let [shadow {:offset-x 10 :offset-y 20 :spread 5 :blur 15}
          scaled (gef/update-shadow-scale shadow 0)]
      (t/is (= 0 (:offset-x scaled)))
      (t/is (= 0 (:offset-y scaled)))
      (t/is (= 0 (:spread scaled)))
      (t/is (= 0 (:blur scaled))))))

(t/deftest update-shadows-scale-test
  (t/testing "Scale all shadows on a shape"
    (let [shape {:shadow [{:offset-x 5 :offset-y 10 :spread 2 :blur 8}
                          {:offset-x 3 :offset-y 6 :spread 1 :blur 4}]}
          scaled (gef/update-shadows-scale shape 3)]
      (let [s1 (first (:shadow scaled))
            s2 (second (:shadow scaled))]
        (t/is (= 15 (:offset-x s1)))
        (t/is (= 30 (:offset-y s1)))
        (t/is (= 6 (:spread s1)))
        (t/is (= 24 (:blur s1)))
        (t/is (= 9 (:offset-x s2)))
        (t/is (= 18 (:offset-y s2))))))

  (t/testing "Empty shadows stays empty"
    (let [shape {:shadow []}
          scaled (gef/update-shadows-scale shape 2)]
      (t/is (empty? (:shadow scaled)))))

  (t/testing "Shape with no :shadow key returns empty vector (mapv on nil)"
    (let [scaled (gef/update-shadows-scale {} 2)]
      (t/is (= [] (:shadow scaled))))))

(t/deftest update-blur-scale-test
  (t/testing "Scale blur by 2"
    (let [shape {:blur {:value 10 :type :blur}}
          scaled (gef/update-blur-scale shape 2)]
      (t/is (= 20 (get-in scaled [:blur :value])))))

  (t/testing "Scale by 1 preserves blur"
    (let [shape {:blur {:value 10 :type :blur}}
          scaled (gef/update-blur-scale shape 1)]
      (t/is (= 10 (get-in scaled [:blur :value])))))

  (t/testing "Scale by 0 zeroes blur"
    (let [shape {:blur {:value 10 :type :blur}}
          scaled (gef/update-blur-scale shape 0)]
      (t/is (= 0 (get-in scaled [:blur :value]))))))
