;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.filters
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn get-filter-id []
  (dm/str "filter-" (uuid/next)))

(defn filter-str
  [filter-id shape]
  (when (or (seq (->> (:shadow shape) (remove :hidden)))
            (and (:blur shape) (-> shape :blur :hidden not)))
    (str/ffmt "url(#%)" filter-id)))

(mf/defc color-matrix
  [{:keys [color]}]
  (let [{:keys [color opacity]} color
        [r g b a] (cc/hex->rgba color opacity)
        [r g b] [(/ r 255) (/ g 255) (/ b 255)]]
    [:feColorMatrix
     {:type "matrix"
      :values (str/ffmt "0 0 0 0 % 0 0 0 0 % 0 0 0 0 % 0 0 0 % 0" r g b a)}]))

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

     (when (< spread 0)
       [:feMorphology {:radius (- spread)
                       :operator "erode"
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
  [{:keys [filter-id params]}]
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

(defn change-filter-in
  "Adds the previous filter as `filter-in` parameter"
  [filters]
  (map #(assoc %1 :filter-in %2) filters (cons nil (map :id filters))))

(defn filter-coords
  [bounds selrect padding]
  (if (or (mth/close? 0.01 (:width selrect))
          (mth/close? 0.01 (:height selrect)))

    ;; We cannot use "objectBoundingbox" if the shape doesn't have width/heigth
    ;; From the SVG spec (https://www.w3.org/TR/SVG11/coords.html#ObjectBoundingBox
    ;; Keyword objectBoundingBox should not be used when the geometry of the applicable element
    ;; has no width or no height, such as the case of a horizontal or vertical line, even when
    ;; the line has actual thickness when viewed due to having a non-zero stroke width since
    ;; stroke width is ignored for bounding box calculations. When the geometry of the
    ;; applicable element has no width or height and objectBoundingBox is specified, then
    ;; the given effect (e.g., a gradient or a filter) will be ignored.
    (let [filter-width  (+ (:width bounds) (* 2 (:horizontal padding)))
          filter-height (+ (:height bounds) (* 2 (:vertical padding)))
          filter-x      (- (:x bounds) #_(:x selrect) (:horizontal padding))
          filter-y      (- (:y bounds) #_(:y selrect) (:vertical padding))
          filter-units  "userSpaceOnUse"]
      [filter-x filter-y filter-width filter-height filter-units])

    ;; If the width/height is not zero we use objectBoundingBox as it's more stable
    (let [filter-width  (/ (+ (:width bounds) (* 2 (:horizontal padding))) (:width selrect))
          filter-height (/ (+ (:height bounds) (* 2 (:vertical padding))) (:height selrect))
          filter-x      (/ (- (:x bounds) (:x selrect) (:horizontal padding)) (:width selrect))
          filter-y      (/ (- (:y bounds) (:y selrect) (:vertical padding)) (:height selrect))
          filter-units  "objectBoundingBox"]
      [filter-x filter-y filter-width filter-height filter-units])))

(mf/defc filters
  [{:keys [filter-id shape]}]

  (let [shape'        (update shape :shadow reverse)
        filters       (-> shape' gsb/shape->filters change-filter-in)
        bounds        (gsb/get-rect-filter-bounds (:selrect shape) filters (or (-> shape :blur :value) 0))
        padding       (gsb/calculate-padding shape)
        selrect       (:selrect shape)

        [filter-x filter-y filter-width filter-height filter-units]
        (filter-coords bounds selrect padding)]

    (when (> (count filters) 2)
      [:filter {:id          filter-id
                :x           filter-x
                :y           filter-y
                :width       filter-width
                :height      filter-height
                :filterUnits filter-units
                :color-interpolation-filters "sRGB"}
       (for [[index entry] (d/enumerate filters)]
         [:& filter-entry {:key (dm/str filter-id "-" index)
                           :entry entry}])])))

