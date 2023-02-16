;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.grid-layout-editor
  (:require
   [app.main.ui.icons :as i]
   [app.common.geom.shapes.grid-layout.layout-data :refer [set-sample-data] ]

   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [rumext.v2 :as mf]))


(mf/defc track-marker
  {::mf/wrap-props false}
  [props]

  (let [center (unchecked-get props "center")
        value (unchecked-get props "value")
        zoom (unchecked-get props "zoom")

        p1 (-> center
               (update :x - (/ 13 zoom))
               (update :y - (/ 16 zoom)))

        p2 (-> p1
               (update :x + (/ 26 zoom)))

        p3 (-> p2
               (update :y + (/ 24 zoom)))
        
        p4 (-> p3
               (update :x - (/ 13 zoom))
               (update :y + (/ 8 zoom)))

        p5 (-> p4
               (update :x - (/ 13 zoom))
               (update :y - (/ 8 zoom)))

        text-x (:x center)
        text-y (:y center)]
    [:g.grid-track-marker
     [:polygon {:points (->> [p1 p2 p3 p4 p5]
                             (map #(dm/fmt "%,%" (:x %) (:y %)))
                             (str/join " "))

                :style {:fill "#DB00FF"
                        :fill-opacity 0.3}}]
     [:text {:x text-x
             :y text-y
             :width (/ 26.26 zoom)
             :height (/ 32 zoom)
             :font-size (/ 16 zoom)
             :text-anchor "middle"
             :dominant-baseline "middle"
             :style {:fill "#DB00FF"}}
      (dm/str value)]]))

(mf/defc editor
  {::mf/wrap-props false}
  [props]

  (let [shape   (unchecked-get props "shape")
        objects (unchecked-get props "objects")
        zoom    (unchecked-get props "zoom")
        bounds  (:points shape)]

    (when (ctl/grid-layout? shape)
      (let [children (->> (cph/get-immediate-children objects (:id shape))
                          (remove :hidden)
                          (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))

            hv     #(gpo/start-hv bounds %)
            vv     #(gpo/start-vv bounds %)

            width  (gpo/width-points bounds)
            height (gpo/height-points bounds)
            origin (gpo/origin bounds)

            {:keys [row-tracks column-tracks shape-cells]}
            (gsg/calc-layout-data shape children bounds)

            [shape children] (set-sample-data shape children)]

        [:g.grid-editor
         [:polygon {:points (->> [origin
                                  (-> origin
                                      (gpt/add (hv width)))
                                  (-> origin
                                      (gpt/add (hv width))
                                      (gpt/subtract (vv (/ 40 zoom))))

                                  (-> origin
                                      (gpt/add (hv width))
                                      (gpt/subtract (vv (/ 40 zoom)))
                                      (gpt/subtract (hv (+ width (/ 40 zoom)))))

                                  (-> origin
                                      (gpt/add (hv width))
                                      (gpt/subtract (vv (/ 40 zoom)))
                                      (gpt/subtract (hv (+ width (/ 40 zoom))))
                                      (gpt/add (vv (+ height (/ 40 zoom)))))
                                  (-> origin
                                      (gpt/add (hv width))
                                      (gpt/subtract (vv (/ 40 zoom)))
                                      (gpt/subtract (hv (+ width (/ 40 zoom))))
                                      (gpt/add (vv (+ height (/ 40 zoom))))
                                      (gpt/add (hv (/ 40 zoom))))]
                                 (map #(dm/fmt "%,%" (:x %) (:y %)))
                                 (str/join " "))
                    :style {:stroke "#DB00FF"
                            :stroke-width (/ 1 zoom)}}]

         (let [start-p (-> origin (gpt/add (hv width)))]
           [:*
            [:rect {:x (:x start-p)
                    :y (- (:y start-p) (/ 40 zoom))
                    :width (/ 40 zoom)
                    :height (/ 40 zoom)
                    :style {:fill "#DB00FF"
                            :stroke "#DB00FF"
                            :stroke-width (/ 1 zoom)}}]

            [:use {:x (+ (:x start-p) (/ 12 zoom))
                   :y (- (:y start-p) (/ 28 zoom))
                   :width (/ 16 zoom)
                   :height (/ 16 zoom)
                   :href (dm/str "#icon-plus")
                   :fill "white"}]])

         (let [start-p (-> origin (gpt/add (vv height)))]
           [:rect {:x (- (:x start-p) (/ 40 zoom))
                   :y (:y start-p)
                   :width (/ 40 zoom)
                   :height (/ 40 zoom)
                   :style {:fill "#DB00FF"
                           :stroke "#DB00FF"
                           :stroke-width (/ 1 zoom)}}])

         (for [[idx column-data] (d/enumerate column-tracks)]
           (let [start-p (-> origin
                             (gpt/add (hv (:distance column-data)))
                             (gpt/subtract (vv (/ 20 zoom))))]
             [:& track-marker {:center start-p
                               :value (dm/str (inc idx))
                               :zoom zoom}]))

         (for [[idx row-data] (d/enumerate row-tracks)]
           (let [start-p (-> origin
                             (gpt/add (vv (:distance row-data)))
                             (gpt/subtract (hv (/ 20 zoom))))]
             [:g {:transform (dm/fmt "rotate(-90 % %)" (:x start-p) (:y start-p))}
              [:& track-marker {:center start-p
                                :value (dm/str (inc idx))
                                :zoom zoom}]]))
         
         (for [[_ grid-cell] (:layout-grid-cells shape)]
           (let [column (nth column-tracks (dec (:column grid-cell)) nil)
                 row (nth row-tracks (dec (:row grid-cell)) nil)

                 start-p (-> origin
                             (gpt/add (hv (:distance column)))
                             (gpt/add (vv (:distance row))))

                 end-p (-> start-p
                           (gpt/add (hv (:value column)))
                           (gpt/add (vv (:value row))))]

             [:*
              #_[:rect {:x (:x start-p)
                        :y (- (:y start-p) (/ 32 zoom) (/ 8 zoom))
                        :width (/ 26.26 zoom)
                        :height (/ 32 zoom)
                        :style {:fill "#DB00FF"
                                :fill-opacity 0.3}
                        }]
              
              [:rect.cell-editor {:x (:x start-p)
                                  :y (:y start-p)
                                  :width (- (:x end-p) (:x start-p))
                                  :height (- (:y end-p) (:y start-p))
                                  :style {:stroke "#DB00FF"
                                          :stroke-dasharray (str/join " " (map #(/ % zoom) [0 8]) )
                                          :stroke-linecap "round"
                                          :stroke-width (/ 2 zoom)}
                                  }]]))]))))
