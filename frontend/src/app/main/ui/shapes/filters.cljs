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
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.color :as color]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]))

(defn get-filter-id []
  (str "filter_" (uuid/next)))

(defn filter-str
  [filter-id shape]

  (when (seq (:shadow shape))
    (str/fmt "url(#$0)" [filter-id])))

(mf/defc color-matrix
  [{:keys [color opacity]}]
  (let [[r g b a] (color/hex->rgba color opacity)
        [r g b] [(/ r 255) (/ g 255) (/ b 255)]]
    [:feColorMatrix
     {:type "matrix"
      :values (str/fmt "0 0 0 0 $0 0 0 0 0 $1 0 0 0 0 $2 0 0 0 $3 0" [r g b a])}]))

(mf/defc drop-shadow-filter
  [{:keys [filter-id filter shape]}]

  (let [{:keys [x y width height]} (:selrect shape)
        {:keys [id in-filter color opacity offset-x offset-y blur spread]} filter]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "dilate"
                       :in "SourceAlpha"
                       :result (str "filter" id)}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]
     [:& color-matrix {:color color :opacity opacity}]

     [:feBlend {:mode "normal"
                :in2 in-filter
                :result (str "filter" id)}]]))

(mf/defc inner-shadow-filter
  [{:keys [filter-id filter shape]}]

  (let [{:keys [x y width height]} (:selrect shape)
        {:keys [id in-filter color opacity offset-x offset-y blur spread]} filter]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"
                      :result "hardAlpha"}]

     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "erode"
                       :in "SourceAlpha"
                       :result (str "filter" id)}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]

     [:feComposite {:in2 "hardAlpha"
                    :operator "arithmetic"
                    :k2 "-1"
                    :k3 "1"}]

     [:& color-matrix {:color color :opacity opacity}]

     [:feBlend {:mode "normal"
                :in2 in-filter
                :result (str "filter" id)}]]))

(defn filter-bounds [shape filter]
  (let [{:keys [x y width height]} (:selrect shape)
        {:keys [offset-x offset-y blur spread] :or {offset-x 0 offset-y 0 blur 0 spread 0}} filter
        filter-x (min x (+ x offset-x (- spread) (- blur) -5))
        filter-y (min y (+ y offset-y (- spread) (- blur) -5))
        filter-width (+ width (mth/abs offset-x) (* spread 2) (* blur 2) 10)
        filter-height (+ height (mth/abs offset-x) (* spread 2) (* blur 2) 10)]
    {:x1 filter-x
     :y1 filter-y
     :x2 (+ filter-x filter-width)
     :y2 (+ filter-y filter-height)}))

(defn get-filters-bounds
  [shape filters]

  (let [filter-bounds (->>
                       filters
                       (filter #(= :drop-shadow (:style %)))
                       (map (partial filter-bounds shape) ))
        ;; We add the selrect so the minimum size will be the selrect
        filter-bounds (conj filter-bounds (:selrect shape))
        x1 (apply min (map :x1 filter-bounds))
        y1 (apply min (map :y1 filter-bounds))
        x2 (apply max (map :x2 filter-bounds))
        y2 (apply max (map :y2 filter-bounds))]
    [x1 y1 (- x2 x1) (- y2 y1)]))

(mf/defc filters
  [{:keys [filter-id shape]}]

  (let [add-in-filter
        (fn [filter in-filter]
          (assoc filter :in-filter in-filter))

        filters (->> shape :shadow (filter (comp not :hidden)))

        [filter-x filter-y filter-width filter-height] (get-filters-bounds shape filters)]
    (when (seq filters)
      [:defs
       [:filter {:id filter-id
                 :x filter-x :y filter-y
                 :width filter-width :height filter-height
                 :filterUnits "userSpaceOnUse"
                 :color-interpolation-filters "sRGB"}

        (let [;; Add as a paramter the input filter
              drop-shadow-filters (->> filters (filter #(= :drop-shadow (:style %))))
              drop-shadow-filters (->> drop-shadow-filters
                                       (map #(str "filter" (:id %)))
                                       (concat ["BackgroundImageFix"])
                                       (map add-in-filter drop-shadow-filters))

              inner-shadow-filters (->> filters (filter #(= :inner-shadow (:style %))))
              inner-shadow-filters (->> inner-shadow-filters
                                       (map #(str "filter" (:id %)))
                                       (concat ["shape"])
                                       (map add-in-filter inner-shadow-filters))]

          [:*
           [:feFlood {:flood-opacity 0 :result "BackgroundImageFix"}]
           (for [{:keys [id type] :as filter} drop-shadow-filters]
             [:& drop-shadow-filter {:key id
                                     :filter-id filter-id
                                     :filter filter
                                     :shape shape}])

           [:feBlend {:mode "normal"
                      :in "SourceGraphic"
                      :in2 (if (seq drop-shadow-filters)
                             (str "filter" (:id (last drop-shadow-filters)))
                             "BackgroundImageFix")
                      :result "shape"}]

           (for [{:keys [id type] :as filter} inner-shadow-filters]
             [:& inner-shadow-filter {:key id
                                     :filter-id filter-id
                                     :filter filter
                                     :shape shape}])
           ])
        ]])))
