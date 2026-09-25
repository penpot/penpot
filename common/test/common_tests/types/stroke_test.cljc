;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.stroke-test
  (:require
   [app.common.types.stroke :as cts]
   [clojure.test :as t]))

;; --- materialize-stroke-side-widths

(t/deftest materialize-with-nil-stroke
  (t/testing "a shape without stroke gets the default stroke with every side concretized"
    (let [stroke (cts/materialize-stroke-side-widths nil #{:stroke-width-top} 10)]
      (t/is (= (:stroke-width-top stroke) 10))
      (t/is (= (:stroke-width-right stroke) 0))
      (t/is (= (:stroke-width-bottom stroke) 0))
      (t/is (= (:stroke-width-left stroke) 0))
      (t/is (= (:stroke-width stroke) 10))
      (t/is (= (:stroke-style stroke) :solid))
      (t/is (= (:stroke-alignment stroke) :inner)))))

(t/deftest materialize-with-global-only-stroke
  (t/testing "editing the top side keeps the other sides on the global width"
    (let [stroke (cts/materialize-stroke-side-widths
                  {:stroke-width 5 :stroke-color "#000000"}
                  #{:stroke-width-top}
                  10)]
      (t/is (= (:stroke-width-top stroke) 10))
      (t/is (= (:stroke-width-right stroke) 5))
      (t/is (= (:stroke-width-bottom stroke) 5))
      (t/is (= (:stroke-width-left stroke) 5))
      (t/is (= (:stroke-width stroke) 10))
      (t/is (= (:stroke-color stroke) "#000000"))))

  (t/testing "editing a non-top side keeps the global width mirroring the top side"
    (let [stroke (cts/materialize-stroke-side-widths
                  {:stroke-width 5}
                  #{:stroke-width-right}
                  10)]
      (t/is (= (:stroke-width-top stroke) 5))
      (t/is (= (:stroke-width-right stroke) 10))
      (t/is (= (:stroke-width-bottom stroke) 5))
      (t/is (= (:stroke-width-left stroke) 5))
      (t/is (= (:stroke-width stroke) 5)))))

(t/deftest materialize-keeps-explicit-sides
  (t/testing "sides with their own value keep it even when it differs from the global width"
    (let [stroke (cts/materialize-stroke-side-widths
                  {:stroke-width 4
                   :stroke-width-top 2
                   :stroke-width-right 3
                   :stroke-width-bottom 6}
                  #{:stroke-width-left}
                  7)]
      (t/is (= (:stroke-width-top stroke) 2))
      (t/is (= (:stroke-width-right stroke) 3))
      (t/is (= (:stroke-width-bottom stroke) 6))
      (t/is (= (:stroke-width-left stroke) 7))
      (t/is (= (:stroke-width stroke) 2)))))

(t/deftest materialize-multiple-sides
  (t/testing "several sides can be edited at once"
    (let [stroke (cts/materialize-stroke-side-widths
                  {:stroke-width 5}
                  #{:stroke-width-top :stroke-width-right}
                  10)]
      (t/is (= (:stroke-width-top stroke) 10))
      (t/is (= (:stroke-width-right stroke) 10))
      (t/is (= (:stroke-width-bottom stroke) 5))
      (t/is (= (:stroke-width-left stroke) 5))
      (t/is (= (:stroke-width stroke) 10)))))
