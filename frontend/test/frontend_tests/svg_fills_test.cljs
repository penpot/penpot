;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.svg-fills-test
  (:require
   [app.common.render-wasm.svg-derived :as svg-derived]
   [app.main.ui.shapes.attrs :as attrs]
   [app.util.object :as obj]
   [cljs.test :refer [deftest is testing]]))

(def sample-shape
  {:selrect {:x 100 :y 200 :width 200 :height 100}
   :svg-attrs {:fill-opacity "0.5"
               :style {:fill "url(#grad)"}}
   :svg-defs {"grad"
              {:tag :radialGradient
               :attrs {:id "grad"
                       :gradient-units "userSpaceOnUse"
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
  (let [fills (svg-derived/svg-fill->fills sample-shape)
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
  (is (nil? (svg-derived/svg-fill->fills {:svg-attrs {:fill "none"}}))))

(def elliptical-shape
  {:selrect {:x 0 :y 0 :width 200 :height 100}
   :svg-attrs {:style {:fill "url(#grad-ellipse)"}}
   :svg-defs {"grad-ellipse"
              {:tag :radialGradient
               :attrs {:id "grad-ellipse"
                       :gradient-units "userSpaceOnUse"
                       :cx "50"
                       :cy "50"
                       :r "50"
                       :gradient-transform "matrix(2 0 0 1 0 0)"}
               :content [{:tag :stop
                          :attrs {:offset "0"
                                  :style "stop-color:#000000;stop-opacity:1"}}
                         {:tag :stop
                          :attrs {:offset "1"
                                  :style "stop-color:#ffffff;stop-opacity:1"}}]}}})

(deftest builds-elliptical-radial-gradient-with-transform
  (let [fills (svg-derived/svg-fill->fills elliptical-shape)
        gradient (get-in (first fills) [:fill-color-gradient])]
    (testing "ellipse from gradientTransform is preserved"
      (is (= 1 (count fills)))
      (is (= :radial (:type gradient)))
      (is (= 0.5 (:start-x gradient)))
      (is (= 0.5 (:start-y gradient)))
      (is (= 0.5 (:end-x gradient)))
      (is (= 1.0 (:end-y gradient)))
      ;; Scaling the X axis in the gradientTransform should reflect on width.
      (is (= 1.0 (:width gradient))))))

(deftest resolve-shape-fills-prefers-existing-fills
  (let [fills [{:fill-color "#ff00ff" :fill-opacity 0.75}]
        resolved (svg-derived/resolve-shape-fills {:fills fills})]
    (is (= fills resolved))))

(deftest resolve-shape-fills-falls-back-to-svg-fill
  (let [resolved (svg-derived/resolve-shape-fills (assoc sample-shape :fills []))]
    (is (= (svg-derived/svg-fill->fills sample-shape) resolved))))

(deftest resolve-shape-fills-defaults-to-black
  (is (= [{:fill-color "#000000" :fill-opacity 1}]
         (svg-derived/resolve-shape-fills {:type :group
                                           :svg-attrs {}}))))

(deftest resolve-shape-fills-accepts-hex-fill
  (let [fills (svg-derived/resolve-shape-fills {:fills []
                                                :type :svg-raw
                                                :svg-attrs {:fill "#fabada"}})]
    (is (= 1 (count fills)))
    (is (= "#fabada" (:fill-color (first fills))))))

;; The classic renderer funnel: stored kebab keys reach React as
;; camelCase props, so a shape renders identical props before and after
;; the kebab migration. Extra attrs keep their source spelling so React
;; passes them to the DOM silently (no unknown-prop warning).
(deftest svg-attrs-render-as-camel-props
  (let [props (attrs/get-svg-props {:svg-attrs {:fill-rule "evenodd"
                                                :stroke-width "2"
                                                :stroke-style "dotted"
                                                :style {:fill-opacity "0.5"}}}
                                   "r1")]
    (is (= "evenodd" (obj/get props "fillRule")))
    (is (= "2" (obj/get props "strokeWidth")))
    (is (= "dotted" (obj/get props "stroke-style")))
    (is (nil? (obj/get props "strokeStyle")) "no camel duplicate for extras")
    (is (= "0.5" (obj/get (obj/get props "style") "fillOpacity")))))


