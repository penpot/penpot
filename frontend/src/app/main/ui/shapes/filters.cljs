;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.filters
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
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

(defn filter-bounds [shape filter-entry]
  (let [{:keys [x y width height]} (:selrect shape)
        {:keys [offset-x offset-y blur spread] :or {offset-x 0 offset-y 0 blur 0 spread 0}} (:params filter-entry)
        filter-x (min x (+ x offset-x (- spread) (- blur) -5))
        filter-y (min y (+ y offset-y (- spread) (- blur) -5))
        filter-width (+ width (mth/abs offset-x) (* spread 2) (* blur 2) 10)
        filter-height (+ height (mth/abs offset-y) (* spread 2) (* blur 2) 10)]
    {:x1 filter-x
     :y1 filter-y
     :x2 (+ filter-x filter-width)
     :y2 (+ filter-y filter-height)}))

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

(defn shape->filters
  [shape]
  (d/concat-vec
   [{:id "BackgroundImageFix" :type :image-fix}]

   ;; Background blur won't work in current SVG specification
   ;; We can revisit this in the future
   #_(->> shape :blur   (blur-filters   :background-blur))

   (->> shape :shadow (shadow-filters :drop-shadow))
   [{:id "shape" :type :blend-filters}]
   (->> shape :shadow (shadow-filters :inner-shadow))
   (->> shape :blur   (blur-filters   :layer-blur))))

(defn get-filters-bounds
  ([shape]
   (let [filters (shape->filters shape)
         blur-value (or (-> shape :blur :value) 0)]
     (get-filters-bounds shape filters blur-value)))

  ([shape filters blur-value]

   (let [svg-root? (and (= :svg-raw (:type shape)) (not= :svg (get-in shape [:content :tag])))
         frame? (= :frame (:type shape))
         {:keys [x y width height]} (:selrect shape)]
     (if svg-root?
       ;; When is a raw-svg but not the root we use the whole svg as bound for the filter. Is the maximum
       ;; we're allowed to display
       {:x 0 :y 0 :width width :height height}

       ;; Otherwise we calculate the bound
       (let [filter-bounds (->> filters
                                (filter #(= :drop-shadow (:type %)))
                                (map (partial filter-bounds shape)))
             ;; We add the selrect so the minimum size will be the selrect
             filter-bounds (conj filter-bounds (-> shape :points gsh/points->selrect))
             x1 (apply min (map :x1 filter-bounds))
             y1 (apply min (map :y1 filter-bounds))
             x2 (apply max (map :x2 filter-bounds))
             y2 (apply max (map :y2 filter-bounds))

             x1 (- x1 (* blur-value 2))
             x2 (+ x2 (* blur-value 2))
             y1 (- y1 (* blur-value 2))
             y2 (+ y2 (* blur-value 2))]

         ;; We should move the frame filter coordinates because they should be
         ;; relative with the frame. By default they come as absolute
         {:x (if frame? (- x1 x) x1)
          :y (if frame? (- y1 y) y1)
          :width (- x2 x1)
          :height (- y2 y1)})))))

(defn calculate-padding [shape]
  (let [stroke-width (case (:stroke-alignment shape :center)
                       :center (/ (:stroke-width shape 0) 2)
                       :outer (:stroke-width shape 0)
                       0)
        margin (gsh/shape-stroke-margin shape stroke-width)]
    (+ stroke-width margin)))

(defn change-filter-in
  "Adds the previous filter as `filter-in` parameter"
  [filters]
  (map #(assoc %1 :filter-in %2) filters (cons nil (map :id filters))))

(mf/defc filters
  [{:keys [filter-id shape]}]

  (let [filters       (-> shape shape->filters change-filter-in)
        bounds        (get-filters-bounds shape filters (or (-> shape :blur :value) 0))
        padding       (calculate-padding shape)
        selrect       (:selrect shape)
        filter-x      (/ (- (:x bounds) (:x selrect) padding) (:width selrect))
        filter-y      (/ (- (:y bounds) (:y selrect) padding) (:height selrect))
        filter-width  (/ (+ (:width bounds) (* 2 padding)) (:width selrect))
        filter-height (/ (+ (:height bounds) (* 2 padding)) (:height selrect))]
    [:*
     (when (> (count filters) 2)
       [:filter {:id          filter-id
                 :x           filter-x
                 :y           filter-y
                 :width       filter-width
                 :height      filter-height
                 :filterUnits "objectBoundingBox"
                 :color-interpolation-filters "sRGB"}
        (for [entry filters]
          [:& filter-entry {:entry entry}])])]))

