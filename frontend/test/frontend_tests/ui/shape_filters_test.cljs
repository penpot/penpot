;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.shape-filters-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.ui.shapes.filters :as filters]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- render-filter
  [shadow-style]
  (let [shape (-> (cts/setup-shape {:type :rect :x 0 :y 0 :width 100 :height 100})
                  (assoc :fills [{:fill-color "#4488ff" :fill-opacity 1}]
                         :shadow [{:id (uuid/next)
                                   :style shadow-style
                                   :offset-x 4 :offset-y 4 :blur 4 :spread 0
                                   :hidden false
                                   :color {:color "#000000" :opacity 0.5}}]))]
    (rds/renderToStaticMarkup
     (mf/element filters/filters* #js {:filterId "filter" :shape shape}))))

(defn- tags
  [markup tag]
  (re-seq (re-pattern (str "<" tag "[^>]*>")) markup))

;; Each filter primitive must name its result and read the previous one, or
;; the chain breaks: an inner shadow then covers the shape fill with the
;; shadow color (#11967).

(t/deftest inner-shadow-filter-chain-is-wired
  (let [markup (render-filter :inner-shadow)
        blends (tags markup "feBlend")]
    (t/is (str/includes? (first (tags markup "feFlood")) "result="))
    (t/is (= 2 (count blends)))
    (t/is (every? #(str/includes? % "in2=") blends))
    (t/is (every? #(str/includes? % "result=") blends))))

(t/deftest drop-shadow-filter-chain-is-wired
  (let [blends (tags (render-filter :drop-shadow) "feBlend")]
    (t/is (every? #(and (str/includes? % "in2=") (str/includes? % "result=")) blends))))
