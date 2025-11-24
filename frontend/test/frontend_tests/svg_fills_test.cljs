;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.svg-fills-test
  (:require
   [app.render-wasm.svg-fills :as svg-fills]
   [cljs.test :refer [deftest is testing]]))

(def sample-shape
  {:selrect {:x 100 :y 200 :width 200 :height 100}
   :svg-attrs {:fillOpacity "0.5"
               :style {:fill "url(#grad)"}}
   :svg-defs {"grad"
              {:tag :radialGradient
               :attrs {:id "grad"
                       :gradientUnits "userSpaceOnUse"
                       :cx "150"
                       :cy "250"
                       :r "50"}
               :content [{:tag :stop
                          :attrs {:offset "0"
                                  :style "stop-color:#ff0000;stop-opacity:1"}}
                         {:tag :stop
                          :attrs {:offset "1"
                                  :style "stop-color:#00ff00;stop-opacity:0"}}]}}})

(deftest builds-gradient-fill-from-svg-defs
  (let [fills (svg-fills/svg-fill->fills sample-shape)
        gradient (get-in (first fills) [:fill-color-gradient])]
    (testing "fallback fill is generated"
      (is (= 1 (count fills))))
    (testing "gradient metadata"
      (is (= :radial (:type gradient)))
      (is (= "#ff0000" (get-in gradient [:stops 0 :color])))
      (is (= "#00ff00" (get-in gradient [:stops 1 :color]))))
    (testing "opacity preserved"
      (is (= 0.5 (:fill-opacity (first fills)))))))

(deftest skips-when-no-svg-fill
  (is (nil? (svg-fills/svg-fill->fills {:svg-attrs {:fill "none"}}))))

(deftest resolve-shape-fills-prefers-existing-fills
  (let [fills [{:fill-color "#ff00ff" :fill-opacity 0.75}]
        resolved (svg-fills/resolve-shape-fills {:fills fills})]
    (is (= fills resolved))))

(deftest resolve-shape-fills-falls-back-to-svg-fill
  (let [resolved (svg-fills/resolve-shape-fills (assoc sample-shape :fills []))]
    (is (= (svg-fills/svg-fill->fills sample-shape) resolved))))

(deftest resolve-shape-fills-defaults-to-black
  (is (= [{:fill-color "#000000" :fill-opacity 1}]
         (svg-fills/resolve-shape-fills {:type :group
                                         :svg-attrs {}}))))

(deftest resolve-shape-fills-accepts-hex-fill
  (let [fills (svg-fills/resolve-shape-fills {:fills []
                                              :type :svg-raw
                                              :svg-attrs {:fill "#fabada"}})]
    (is (= 1 (count fills)))
    (is (= "#fabada" (:fill-color (first fills))))))


