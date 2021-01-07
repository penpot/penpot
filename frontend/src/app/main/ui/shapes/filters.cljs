;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.filters
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.util.color :as color]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn get-filter-id []
  (str "filter_" (uuid/next)))

(defn filter-str
  [filter-id shape]

  (when (or (seq (->> (:shadow shape) (remove :hidden)))
            (and (:blur shape) (-> shape :blur :hidden not)))
    (str/fmt "url(#$0)" [filter-id])))

(mf/defc color-matrix
  [{:keys [color]}]
  (let [{:keys [color opacity]} color
        [r g b a] (color/hex->rgba color opacity)
        [r g b] [(/ r 255) (/ g 255) (/ b 255)]]
    [:feColorMatrix
     {:type "matrix"
      :values (str/fmt "0 0 0 0 $0 0 0 0 0 $1 0 0 0 0 $2 0 0 0 $3 0" [r g b a])}]))

(mf/defc drop-shadow-filter
  [{:keys [filter-in filter-id params]}]

  (let [{:keys [color offset-x offset-y blur spread]} params]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "dilate"
                       :in "SourceAlpha"
                       :result filter-id}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]
     [:& color-matrix {:color color}]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

(mf/defc inner-shadow-filter
  [{:keys [filter-in filter-id params]}]

  (let [{:keys [color offset-x offset-y blur spread]} params]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"
                      :result "hardAlpha"}]

     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "erode"
                       :in "SourceAlpha"
                       :result filter-id}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]

     [:feComposite {:in2 "hardAlpha"
                    :operator "arithmetic"
                    :k2 "-1"
                    :k3 "1"}]

     [:& color-matrix {:color color}]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

(mf/defc background-blur-filter
  [{:keys [filter-id filter-in params]}]
  [:*
   [:feGaussianBlur {:in "BackgroundImage"
                     :stdDeviation (/ (:value params) 2)}]
   [:feComposite {:in2 "SourceAlpha"
                  :operator "in"
                  :result filter-id}]])

(mf/defc layer-blur-filter
  [{:keys [filter-id params]}]

  [:feGaussianBlur {:stdDeviation (:value params)
                    :result filter-id}])

(mf/defc image-fix-filter [{:keys [filter-id]}]
  [:feFlood {:flood-opacity 0 :result filter-id}])

(mf/defc blend-filters [{:keys [filter-id filter-in]}]
  [:feBlend {:mode "normal"
             :in "SourceGraphic"
             :in2 filter-in
             :result filter-id}])

(defn blur-filters [type value]
  (->> [value]
       (remove :hidden)
       (filter #(= (:type %) type))
       (map #(hash-map :id (str "filter_" (:id %))
                       :type (:type %)
                       :params %))))

(defn shadow-filters [type filters]
  (->> filters
       (remove :hidden)
       (filter #(= (:style %) type))
       (map #(hash-map :id (str "filter_" (:id %))
                       :type (:style %)
                       :params %))))

(mf/defc filter-entry [{:keys [entry]}]
  (let [props #js {:filter-id (:id entry)
                   :filter-in (:filter-in entry)
                   :params (:params entry)}]
    (case (:type entry)
      :drop-shadow [:> drop-shadow-filter props]
      :inner-shadow [:> inner-shadow-filter props]
      :background-blur [:> background-blur-filter props]
      :layer-blur [:> layer-blur-filter props]
      :image-fix [:> image-fix-filter props]
      :blend-filters [:> blend-filters props])))

(mf/defc filters
  [{:keys [filter-id shape]}]

  (let [filters (d/concat
                 []
                 [{:id "BackgroundImageFix" :type :image-fix}]

                 ;; Background blur won't work in current SVG specification
                 ;; We can revisit this in the future
                 #_(->> shape :blur   (blur-filters   :background-blur))

                 (->> shape :shadow (shadow-filters :drop-shadow))
                 [{:id "shape" :type :blend-filters}]
                 (->> shape :shadow (shadow-filters :inner-shadow))
                 (->> shape :blur   (blur-filters   :layer-blur)))

        ;; Adds the previous filter as `filter-in` parameter
        filters (map #(assoc %1 :filter-in %2) filters (cons nil (map :id filters)))]

    [:*
     (when (> (count filters) 2)
       [:filter {:id filter-id
                 :color-interpolation-filters "sRGB"}

        (for [entry filters]
          [:& filter-entry {:entry entry}])])]))

