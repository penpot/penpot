;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.util.object :as obj]
   [app.util.path.bool :as pb]
   [app.util.path.geom :as upg]
   [app.util.path.shapes-to-path :as stp]
   [clojure.set :as set]
   [rumext.alpha :as mf]))

(mf/defc path-points
  [{:keys [points color]}]

  [:*
   (for [[idx {:keys [x y]}] (d/enumerate points)]
     [:circle {:key (str "circle-" idx)
               :cx x
               :cy y
               :r 5
               :style {:fill color
                       ;;:fillOpacity 0.5
                       }}])])

(defn bool-shape
  [shape-wrapper]
  (mf/fnc bool-shape
    {::mf/wrap-props false}
    [props]
    (let [frame  (obj/get props "frame")
          childs (obj/get props "childs")
          shape-1 (stp/convert-to-path (nth childs 0))
          shape-2 (stp/convert-to-path (nth childs 1))

          content-1 (-> shape-1 gsh/transform-shape (gsh/translate-to-frame frame) :content)
          content-2 (-> shape-2 gsh/transform-shape (gsh/translate-to-frame frame) :content)
          

          [content-1' content-2'] (pb/content-intersect-split content-1 content-2)
          
          points-1 (->> (upg/content->points content-1')
                        (map #(hash-map :x (mth/round (:x %))
                                        :y (mth/round (:y %))))
                        (into #{}))
          
          points-2 (->> (upg/content->points content-2')
                        (map #(hash-map :x (mth/round (:x %))
                                        :y (mth/round (:y %))))
                        (into #{}))

          points-3 (set/intersection points-1 points-2)]

      [:*
       [:& shape-wrapper {:shape (-> shape-1 #_(assoc :content content-1'))
                          :frame frame}]

       [:& shape-wrapper {:shape (-> shape-2 #_(assoc :content content-2'))
                          :frame frame}]

       [:& path-points {:points points-1 :color "#FF0000"}]
       [:& path-points {:points points-2 :color "#0000FF"}]
       [:& path-points {:points points-3 :color "#FF00FF"}]


       ])))



