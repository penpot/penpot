;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.svg-filters-test
  (:require
   [app.common.render-wasm.svg-derived :as svg-derived]
   [cljs.test :refer [deftest is testing]]))

(def sample-filter-shape
  {:svg-attrs {:filter "url(#simple-filter)"}
   :svg-defs {"simple-filter"
              {:tag :filter
               :content [{:tag :feOffset :attrs {:dx "2" :dy "3"}}
                         {:tag :feGaussianBlur :attrs {:stdDeviation "4"}}]}}})

(defn- filter-shape
  [content]
  {:svg-attrs {:filter "url(#f)"}
   :svg-defs {"f" {:tag :filter :content content}}})

(defn- derived-shadow
  [content]
  (->> (svg-derived/apply-svg-filters (filter-shape content))
       :shadow
       (map #(dissoc % :id))))

(deftest derives-shadow-from-svg-filter
  (let [shape  (svg-derived/apply-svg-filters sample-filter-shape)
        shadow (:shadow shape)]
    (testing "the shadow's blur does not blur the shape"
      (is (nil? (:blur shape))))
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

(deftest derives-layer-blur-from-plain-blur-filter
  (let [shape (svg-derived/apply-svg-filters
               (filter-shape [{:tag :feGaussianBlur :attrs {:stdDeviation "4"}}]))]
    (is (= :layer-blur (get-in shape [:blur :type])))
    (is (= 4.0 (get-in shape [:blur :value])))
    (is (nil? (:shadow shape)))))

(deftest shadow-color-from-color-matrix
  (is (= [{:color "#ff0000" :opacity 0.3}]
         (map :color (derived-shadow
                      [{:tag :feOffset :attrs {:in "SourceAlpha" :dx "12" :dy "12"}}
                       {:tag :feGaussianBlur :attrs {:stdDeviation "4"}}
                       {:tag :feColorMatrix
                        :attrs {:type "matrix"
                                :values "0 0 0 0 1  0 0 0 0 0  0 0 0 0 0  0 0 0 0.3 0"}}])))))

(deftest shadow-color-ignores-hard-alpha-matrix-before-offset
  ;; Figma: a hard-alpha matrix before the offset, the color matrix after it.
  (is (= [{:color "#000000" :opacity 0.25}]
         (map :color (derived-shadow
                      [{:tag :feFlood :attrs {:flood-opacity "0"}}
                       {:tag :feColorMatrix
                        :attrs {:in "SourceAlpha"
                                :type "matrix"
                                :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}}
                       {:tag :feOffset :attrs {:dy "4"}}
                       {:tag :feGaussianBlur :attrs {:stdDeviation "2"}}
                       {:tag :feColorMatrix
                        :attrs {:type "matrix"
                                :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0.25 0"}}])))))

(deftest shadow-color-from-flood
  (is (= [{:color "#00ff00" :opacity 0.5}]
         (map :color (derived-shadow
                      [{:tag :feOffset :attrs {:dx "2" :dy "2"}}
                       {:tag :feGaussianBlur :attrs {:stdDeviation "2"}}
                       {:tag :feFlood :attrs {:flood-color "#00ff00" :flood-opacity "0.5"}}])))))

(deftest derives-shadow-from-fe-drop-shadow
  (let [content [{:tag :feDropShadow
                  :attrs {:dx "12" :dy "8" :stdDeviation "4"
                          :flood-color "#000" :flood-opacity "0.3"}}]
        shape   (svg-derived/apply-svg-filters (filter-shape content))]
    (is (nil? (:blur shape)))
    (is (= [{:style :drop-shadow
             :offset-x 12
             :offset-y 8
             :blur 8
             :spread 0
             :hidden false
             :color {:color "#000000" :opacity 0.3}}]
           (derived-shadow content)))))

(deftest fe-drop-shadow-uses-spec-defaults
  (is (= [{:offset-x 2 :offset-y 2 :blur 4 :color {:color "#000000" :opacity 1}}]
         (map #(select-keys % [:offset-x :offset-y :blur :color])
              (derived-shadow [{:tag :feDropShadow :attrs {}}])))))

(deftest keeps-existing-native-filters
  (let [existing {:blur {:id :existing :type :layer-blur :value 1.0}
                  :shadow [{:id :shadow :style :drop-shadow}]}
        shape    (svg-derived/apply-svg-filters (merge sample-filter-shape existing))]
    (is (= (:blur existing) (:blur shape)))
    (is (= (:shadow existing) (:shadow shape)))))

(deftest skips-when-no-filter-definition
  (let [shape {:svg-attrs {:fill "#fff"}}
        result (svg-derived/apply-svg-filters shape)]
    (is (= shape result))))

