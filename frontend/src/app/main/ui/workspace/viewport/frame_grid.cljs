;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.frame-grid
  (:require
   [app.common.data :as d]
   [app.common.geom.grid :as gg]
   [app.common.math :as mth]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [rumext.v2 :as mf]))

(mf/defc square-grid [{:keys [frame zoom grid] :as props}]
  (let [grid-id (mf/use-memo #(uuid/next))
        {:keys [size] :as params} (-> grid :params)
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

(mf/defc layout-grid
  [{:keys [key frame grid zoom]}]
  (let [{color-value :color color-opacity :opacity} (-> grid :params :color)
        ;; Support for old color format
        color-value (or color-value (:value (get-in grid [:params :color :value])))
        gutter (gg/grid-gutter frame grid)
        gutter? (and (not (nil? gutter)) (not (mth/almost-zero? gutter)))]

    [:g.grid
     (for [[idx {:keys [x y width height] :as area}] (d/enumerate (gg/grid-areas frame grid))]
       (cond
         gutter?
         [:rect {:key (str key "-" x "-" y)
                 :x x
                 :y y
                 :width (- (+ x width) x)
                 :height (- (+ y height) y)
                 :style {:fill color-value
                         :stroke-width 0
                         :opacity color-opacity}}]

         (and (not gutter?) (= :column (:type grid)))
         [:*
          (when (= idx 0)
            [:line {:key (str key "-" x "-" y "-start")
                    :x1 x
                    :y1 y
                    :x2 x
                    :y2 (+ y height)
                    :style {:stroke color-value
                            :stroke-width (/ 1 zoom)
                            :strokeOpacity color-opacity
                            :fill "none"}}])

          [:line {:key (str key "-" x "-" y "-end")
                  :x1 (+ x width)
                  :y1 y
                  :x2 (+ x width)
                  :y2 (+ y height)
                  :style {:stroke color-value
                          :stroke-width (/ 1 zoom)
                          :strokeOpacity color-opacity
                          :fill "none"}}]]

         (and (not gutter?) (= :row (:type grid)))
         [:*
          (when (= idx 0)
            [:line {:key (str key "-" x "-" y "-start")
                    :x1 x
                    :y1 y
                    :x2 (+ x width)
                    :y2 y
                    :style {:stroke color-value
                            :stroke-width (/ 1 zoom)
                            :strokeOpacity color-opacity
                            :fill "none"}}])

          [:line {:key (str key "-" x "-" y "-end")
                  :x1 x
                  :y1 (+ y height)
                  :x2 (+ x width)
                  :y2 (+ y height)
                  :style {:stroke color-value
                          :stroke-width (/ 1 zoom)
                          :strokeOpacity color-opacity
                          :fill "none"}}]]))]))

(mf/defc grid-display-frame
  [{:keys [frame zoom]}]
  (for [[index grid] (->> (:grids frame)
                                                     (filter :display)
                                                     (map-indexed vector))]
    (let [props #js {:key (str (:id frame) "-grid-" index)
                     :frame frame
                     :zoom zoom
                     :grid grid}]
      (case (:type grid)
        :square [:> square-grid props]
        :column [:> layout-grid props]
        :row    [:> layout-grid props]))))

(mf/defc frame-grid
  {::mf/wrap [mf/memo]}
  [{:keys [zoom transform selected focus]}]
  (let [frames        (mf/deref refs/workspace-frames)
        transforming  (when (some? transform) selected)
        is-transform? #(contains? transforming (:id %))]

    [:g.grid-display {:style {:pointer-events "none"}}
     (for [frame frames]
       (when (and (not (is-transform? frame))
                  (not (ctst/rotated-frame? frame))
                  (or (empty? focus) (contains? focus (:id frame))))
         [:& grid-display-frame {:key (str "grid-" (:id frame))
                                 :zoom zoom
                                 :frame frame}]))]))
