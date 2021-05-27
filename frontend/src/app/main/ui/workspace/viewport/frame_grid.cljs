;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.frame-grid
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.main.refs :as refs]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.geom.shapes :as gsh]
   [app.util.geom.grid :as gg]
   [app.common.uuid :as uuid]))

(mf/defc square-grid [{:keys [frame zoom grid] :as props}]
  (let [grid-id (mf/use-memo #(uuid/next))
        {:keys [color size] :as params} (-> grid :params)
        {color-value :color color-opacity :opacity} (-> grid :params :color)
        ;; Support for old color format
        color-value (or color-value (:value (get-in grid [:params :color :value])))]

    [:g.grid
     [:defs
      [:pattern {:id grid-id
                 :x (:x frame)
                 :y (:y frame)
                 :width size
                 :height size
                 :pattern-units "userSpaceOnUse"}
       [:path {:d (str "M " size " " 0 " "
                       "L " 0 " " 0 " " 0 " " size " ")
               :style {:fill "none"
                       :stroke color-value
                       :stroke-opacity color-opacity
                       :stroke-width (str (/ 1 zoom))}}]]]

     [:rect {:x (:x frame)
             :y (:y frame)
             :width (:width frame)
             :height (:height frame)
             :fill (str "url(#" grid-id ")")}]]))

(mf/defc layout-grid [{:keys [key frame zoom grid]}]
  (let [{color-value :color color-opacity :opacity} (-> grid :params :color)
        ;; Support for old color format
        color-value (or color-value (:value (get-in grid [:params :color :value])))
        gutter (-> grid :params :gutter)
        gutter? (and (not (nil? gutter)) (not= gutter 0))

        style (if gutter?
                #js {:fill color-value
                     :opacity color-opacity}
                #js {:stroke color-value
                     :strokeOpacity color-opacity
                     :fill "none"})]
    [:g.grid
     (for [{:keys [x y width height]} (gg/grid-areas frame grid)]
       (do
         [:rect {:key (str key "-" x "-" y)
                 :x (mth/round x)
                 :y (mth/round y)
                 :width (- (mth/round (+ x width)) (mth/round x))
                 :height (- (mth/round (+ y height)) (mth/round y))
                 :style style}]))]))

(mf/defc grid-display-frame [{:keys [frame zoom]}]
  (let [grids (:grids frame)]
    (for [[index {:keys [type display] :as grid}] (map-indexed vector grids)]
      (let [props #js {:key (str (:id frame) "-grid-" index)
                       :frame frame
                       :zoom zoom
                       :grid grid}]
        (when display
          (case type
            :square [:> square-grid props]
            :column [:> layout-grid props]
            :row    [:> layout-grid props]))))))


(def shapes-moving-ref
  (let [moving-shapes (fn [local]
                        (when (= :move (:transform local))
                          (:selected local)))]
    (l/derived moving-shapes refs/workspace-local)))

(mf/defc frame-grid
  {::mf/wrap [mf/memo]}
  [{:keys [zoom]}]
  (let [frames (mf/deref refs/workspace-frames)
        shapes-moving (mf/deref shapes-moving-ref)]
    [:g.grid-display {:style {:pointer-events "none"}}
     (for [frame (->> frames (remove #(contains? shapes-moving (:id %))))]
       [:& grid-display-frame {:key (str "grid-" (:id frame))
                               :zoom zoom
                               :frame (gsh/transform-shape frame)}])]))
