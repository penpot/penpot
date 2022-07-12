;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.bounds
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes.rect :as gsr]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]))

(defn shape-stroke-margin
  [shape stroke-width]
  (if (= (:type shape) :path)
    ;; TODO: Calculate with the stroke offset (not implemented yet
    (mth/sqrt (* 2 stroke-width stroke-width))
    (- (mth/sqrt (* 2 stroke-width stroke-width)) stroke-width)))

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

(defn calculate-filter-bounds [{:keys [x y width height]} filter-entry]
  (let [{:keys [offset-x offset-y blur spread] :or {offset-x 0 offset-y 0 blur 0 spread 0}} (:params filter-entry)
        filter-x (min x (+ x offset-x (- spread) (- blur) -5))
        filter-y (min y (+ y offset-y (- spread) (- blur) -5))
        filter-width (+ width (mth/abs offset-x) (* spread 2) (* blur 2) 10)
        filter-height (+ height (mth/abs offset-y) (* spread 2) (* blur 2) 10)]
    (gsr/make-selrect filter-x filter-y filter-width filter-height)))

(defn get-rect-filter-bounds
  [selrect filters blur-value]
  (let [filter-bounds (->> filters
                           (filter #(= :drop-shadow (:type %)))
                           (map (partial calculate-filter-bounds selrect))
                           (concat [selrect])
                           (gsr/join-selrects))
        delta-blur (* blur-value 2)

        result
        (-> filter-bounds
            (update :x - delta-blur)
            (update :y - delta-blur)
            (update :x1 - delta-blur)
            (update :x1 - delta-blur)
            (update :x2 + delta-blur)
            (update :y2 + delta-blur)
            (update :width + (* delta-blur 2))
            (update :height + (* delta-blur 2)))]

    result))

(defn get-shape-filter-bounds
  ([shape]
   (let [svg-root? (and (= :svg-raw (:type shape)) (not= :svg (get-in shape [:content :tag])))]
     (if svg-root?
       (:selrect shape)

       (let [filters (shape->filters shape)
             blur-value (or (-> shape :blur :value) 0)]
         (get-rect-filter-bounds (-> shape :points gsr/points->selrect) filters blur-value))))))

(defn calculate-padding
  ([shape]
   (calculate-padding shape false))

  ([shape ignore-margin?]
   (let [stroke-width (apply max 0 (map #(case (:stroke-alignment % :center)
                                           :center (/ (:stroke-width % 0) 2)
                                           :outer (:stroke-width % 0)
                                           0) (:strokes shape)))

         margin (if ignore-margin?
                  0
                  (apply max 0 (map #(shape-stroke-margin % stroke-width) (:strokes shape))))

         shadow-width (apply max 0 (map #(case (:style % :drop-shadow)
                                           :drop-shadow (+ (mth/abs (:offset-x %)) (* (:spread %) 2) (* (:blur %) 2) 10)
                                           0) (:shadow shape)))

         shadow-height (apply max 0 (map #(case (:style % :drop-shadow)
                                            :drop-shadow (+ (mth/abs (:offset-y %)) (* (:spread %) 2) (* (:blur %) 2) 10)
                                            0) (:shadow shape)))]

     {:horizontal (+ stroke-width margin shadow-width)
      :vertical (+ stroke-width margin shadow-height)})))

(defn- add-padding
  [bounds padding]
  (-> bounds
      (update :x - (:horizontal padding))
      (update :y - (:vertical padding))
      (update :width + (* 2 (:horizontal padding)))
      (update :height + (* 2 (:vertical padding)))))

(defn get-object-bounds
  [objects shape]

  (let [calculate-base-bounds
        (fn [shape]
          (-> (get-shape-filter-bounds shape)
              (add-padding (calculate-padding shape true))))

        bounds
        (cph/reduce-objects
         objects
         (fn [shape]
           (and (d/not-empty? (:shapes shape))
                (or (not (cph/frame-shape? shape))
                    (:show-content shape))

                (or (not (cph/group-shape? shape))
                    (not (:masked-group? shape)))))

         (:id shape)

         (fn [result shape]
           (conj result (get-object-bounds objects shape)))

         [(calculate-base-bounds shape)])


        children-bounds (or (:children-bounds shape) (gsr/join-selrects bounds))

        filters (shape->filters shape)
        blur-value (or (-> shape :blur :value) 0)]

    (get-rect-filter-bounds children-bounds filters blur-value)))

