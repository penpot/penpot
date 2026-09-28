;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.renderer-svg-test
  (:require
   [app.renderer.svg-gradient :as svg-gradient]
   [app.wasm :as wasm-io]
   [app.wasm.serialize :as serialize]
   [cljs.test :refer [deftest is testing async]]
   [clojure.string :as str]
   [promesa.core :as p]))

(def gradient-stops
  [{"color" "#000000" "offset" 0 "opacity" 1}
   {"color" "#ffffff" "offset" 1 "opacity" 1}])

(deftest creates-the-correct-gradient-element
  (doseq [[gradient-type element-name]
          [["linear" "linearGradient"]
           ["radial" "radialGradient"]]]
    (testing gradient-type
      (let [gradient-data {"type" "gradient"
                           "gradient" {"type" gradient-type
                                       "stops" gradient-stops}}
            result         (svg-gradient/data->gradient-def "text-id" ["#000001" gradient-data])]
        (is (= element-name (get result "name")))))))


(def ^:private svg-red-id (random-uuid))
(def ^:private blue-id (random-uuid))

(defn- scene []
  {svg-red-id {:id svg-red-id
               :type :rect
               :name "svg-red"
               :x 10 :y 10 :width 100 :height 100
               :rotation 0
               :selrect {:x 10 :y 10 :x1 10 :y1 10 :x2 110 :y2 110
                         :width 100 :height 100}
               :strokes []
               :svg-attrs {:fill "#ff0000"}}
   blue-id {:id blue-id
            :type :rect
            :name "user-blue"
            :x 130 :y 10 :width 100 :height 100
            :rotation 0
            :selrect {:x 130 :y 10 :x1 130 :y1 10 :x2 230 :y2 110
                      :width 100 :height 100}
            :strokes []
            :fills [{:fill-color "#0000ff" :fill-opacity 1}]}})

(defn- render-svg-string
  [shape-id]
  (.toString (js/Buffer.from (wasm-io/render-shape-svg shape-id 1))))

(deftest exporter-honours-svg-attr-fills
  (async done
    (->> (wasm-io/init!)
         (p/mcat
          (fn [_]
            (serialize/serialize-scene! (scene))
            (let [red (render-svg-string svg-red-id)
                  blue (render-svg-string blue-id)]
              (is (str/includes? blue "blue")
                  "control: user fills render, so the harness works")
              (is (str/includes? red "fill=\"red\"")
                  "svg-attr fill survives headless serialization")
              (p/resolved nil))))
         (p/mcat (fn [_] (done))))))
