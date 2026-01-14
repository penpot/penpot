;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.svg-filters-test
  (:require
   [app.render-wasm.svg-filters :as svg-filters]
   [cljs.test :refer [deftest is testing]]))

(def sample-filter-shape
  {:svg-attrs {:filter "url(#simple-filter)"}
   :svg-defs {"simple-filter"
              {:tag :filter
               :content [{:tag :feOffset :attrs {:dx "2" :dy "3"}}
                         {:tag :feGaussianBlur :attrs {:stdDeviation "4"}}]}}})

(deftest derives-blur-and-shadow-from-svg-filter
  (let [shape  (svg-filters/apply-svg-filters sample-filter-shape)
        blur   (:blur shape)
        shadow (:shadow shape)]
    (testing "layer blur derived from feGaussianBlur"
      (is (= :layer-blur (:type blur)))
      (is (= 4.0 (:value blur))))
    (testing "drop shadow derived from filter chain"
      (is (= [{:style :drop-shadow
               :offset-x 2.0
               :offset-y 3.0
               :blur 8.0
               :spread 0
               :hidden false
               :color {:color "#000000" :opacity 1}}]
             (map #(dissoc % :id) shadow))))
    (testing "svg attrs remain intact"
      (is (= "url(#simple-filter)" (get-in shape [:svg-attrs :filter]))))))

(deftest keeps-existing-native-filters
  (let [existing {:blur {:id :existing :type :layer-blur :value 1.0}
                  :shadow [{:id :shadow :style :drop-shadow}]}
        shape    (svg-filters/apply-svg-filters (merge sample-filter-shape existing))]
    (is (= (:blur existing) (:blur shape)))
    (is (= (:shadow existing) (:shadow shape)))))

(deftest skips-when-no-filter-definition
  (let [shape {:svg-attrs {:fill "#fff"}}
        result (svg-filters/apply-svg-filters shape)]
    (is (= shape result))))

