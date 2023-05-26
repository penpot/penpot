;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.bounds
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]))

(defn shape-stroke-margin
  [shape stroke-width]
  (if (cph/path-shape? shape)
    ;; TODO: Calculate with the stroke offset (not implemented yet
    (mth/sqrt (* 2 stroke-width stroke-width))
    (- (mth/sqrt (* 2 stroke-width stroke-width)) stroke-width)))

(defn- apply-filters
  [type filters]
  (sequence
   (comp
    (remove :hidden)
    (filter #(= (:style %) type))
    (map (fn [item]
           {:id (dm/str "filter_" (:id item))
            :type type
            :params item})))
   filters))

(defn shape->filters
  [shape]
  (d/concat-vec
   [{:id "BackgroundImageFix" :type :image-fix}]

   ;; Background blur won't work in current SVG specification
   ;; We can revisit this in the future
   #_(->> shape :blur (into []) (blur-filters :background-blur))

   (->> shape :shadow (apply-filters :drop-shadow))
   [{:id "shape" :type :blend-filters}]
   (->> shape :shadow (apply-filters :inner-shadow))
   (->> shape :blur   (into []) (apply-filters :layer-blur))))

(defn- calculate-filter-bounds
  [selrect filter-entry]
  (let [x (dm/get-prop selrect :x)
        y (dm/get-prop selrect :y)
        w (dm/get-prop selrect :width)
        h (dm/get-prop selrect :height)

        {:keys [offset-x offset-y blur spread]
         :or {offset-x 0 offset-y 0 blur 0 spread 0}}
        (:params filter-entry)

        filter-x (mth/min x (+ x offset-x (- spread) (- blur) -5))
        filter-y (mth/min y (+ y offset-y (- spread) (- blur) -5))
        filter-w (+ w (mth/abs offset-x) (* spread 2) (* blur 2) 10)
        filter-h (+ h (mth/abs offset-y) (* spread 2) (* blur 2) 10)]
    (grc/make-rect filter-x filter-y filter-w filter-h)))

(defn get-rect-filter-bounds
  [selrect filters blur-value]
  (let [bounds-xf  (comp
                    (filter #(= :drop-shadow (:type %)))
                    (map (partial calculate-filter-bounds selrect)))
        delta-blur (* blur-value 2)]
    (-> (into [selrect] bounds-xf filters)
        (grc/join-rects)
        (update :x - delta-blur)
        (update :y - delta-blur)
        (update :x1 - delta-blur)
        (update :y1 - delta-blur)
        (update :x2 + delta-blur)
        (update :y2 + delta-blur)
        (update :width + (* delta-blur 2))
        (update :height + (* delta-blur 2)))))

(defn get-shape-filter-bounds
  [shape]
  (if (and (cph/svg-raw-shape? shape)
           (not= :svg (dm/get-in shape [:content :tag])))
    (dm/get-prop shape :selrect)
    (let [filters    (shape->filters shape)
          blur-value (or (-> shape :blur :value) 0)
          srect      (-> (dm/get-prop shape :points)
                         (grc/points->rect))]
      (get-rect-filter-bounds srect filters blur-value))))

(defn calculate-padding
  ([shape]
   (calculate-padding shape false))
  ([shape ignore-margin?]
   (let [strokes (:strokes shape)

         stroke-width
         (->> strokes
              (map #(case (get % :stroke-alignment :center)
                      :center (/ (:stroke-width % 0) 2)
                      :outer  (:stroke-width % 0)
                      0))
              (reduce d/max 0))

         margin
         (if ignore-margin?
           0
           (->> strokes
                (map #(shape-stroke-margin % stroke-width))
                (reduce d/max 0)))

         shadow-width
         (->> (:shadow shape)
              (map #(case (:style % :drop-shadow)
                      :drop-shadow (+ (mth/abs (:offset-x %)) (* (:spread %) 2) (* (:blur %) 2) 10)
                      0))
              (reduce d/max 0))

         shadow-height
         (->> (:shadow shape)
              (map #(case (:style % :drop-shadow)
                      :drop-shadow (+ (mth/abs (:offset-y %)) (* (:spread %) 2) (* (:blur %) 2) 10)
                      0))
              (reduce d/max 0))]

     {:horizontal (+ stroke-width margin shadow-width)
      :vertical (+ stroke-width margin shadow-height)})))

(defn- add-padding
  [bounds padding]
  (let [h-padding (:horizontal padding)
        v-padding (:vertical padding)]
    (-> bounds
        (update :x - h-padding)
        (update :x1 - h-padding)
        (update :x2 + h-padding)
        (update :y - v-padding)
        (update :y1 - v-padding)
        (update :y2 + v-padding)
        (update :width + (* 2 h-padding))
        (update :height + (* 2 v-padding)))))

(defn get-object-bounds
  [objects shape]
  (let [calculate-base-bounds
        (fn [shape]
          (-> (get-shape-filter-bounds shape)
              (add-padding (calculate-padding shape true))))

        bounds
        (cond
          (empty? (:shapes shape))
          [(calculate-base-bounds shape)]

          (:masked-group? shape)
          [(calculate-base-bounds shape)]

          (and (cph/frame-shape? shape) (not (:show-content shape)))
          [(calculate-base-bounds shape)]

          :else
          (cph/reduce-objects
           objects
           (fn [shape]
             (and (d/not-empty? (:shapes shape))
                  (or (not (cph/frame-shape? shape))
                      (:show-content shape))

                  (or (not (cph/group-shape? shape))
                      (not (:masked-group? shape)))))
           (:id shape)
           (fn [result child]
             (conj result (calculate-base-bounds child)))

           [(calculate-base-bounds shape)]))

        children-bounds
        (cond->> (grc/join-rects bounds)
          (not (cph/frame-shape? shape)) (or (:children-bounds shape)))

        filters (shape->filters shape)
        blur-value (or (-> shape :blur :value) 0)]

    (get-rect-filter-bounds children-bounds filters blur-value)))

