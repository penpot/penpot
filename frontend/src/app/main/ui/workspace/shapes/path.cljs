;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.path
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [app.main.streams :as ms]
   [app.main.constants :as c]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dr]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :as common]
   [app.util.geom.path :as ugp]
   [app.common.geom.shapes.path :as gsp]))

(mf/defc path-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        hover? (or (mf/deref refs/current-hover) #{})

        on-mouse-down   (mf/use-callback
                         (mf/deps shape)
                         #(common/on-mouse-down % shape))
        on-context-menu (mf/use-callback
                         (mf/deps shape)
                         #(common/on-context-menu % shape))

        on-double-click (mf/use-callback
                         (mf/deps shape)
                         (fn [event]
                           (prn "?? PATH")
                           (when (and (not (::dr/initialized? shape)) (hover? (:id shape)))
                             (do
                               (dom/stop-propagation event)
                               (dom/prevent-default event)
                               (st/emit! (dw/start-edition-mode (:id shape)))))))]

    [:> shape-container {:shape shape
                         :on-double-click on-double-click
                         :on-mouse-down on-mouse-down
                         :on-context-menu on-context-menu}
     [:& path/path-shape {:shape shape
                          :background? true}]]))


(mf/defc path-handler [{:keys [point handler zoom selected]}]
  (when (and point handler)
    (let [{:keys [x y]} handler]
      [:g.handler
       [:line
        {:x1 (:x point)
         :y1 (:y point)
         :x2 x
         :y2 y
         :style {:stroke "#B1B2B5"
                 :stroke-width (/ 1 zoom)}}]
       [:rect
        {:x (- x (/ 3 zoom))
         :y (- y (/ 3 zoom))
         :width (/ 6 zoom)
         :height (/ 6 zoom)
         :style {:stroke-width (/ 1 zoom)
                 :stroke (if selected "#000000" "#1FDEA7")
                 :fill (if selected "#1FDEA7" "#FFFFFF")}}]])))

(mf/defc path-editor
  [{:keys [shape zoom]}]

  (let [points (:points shape)
        drag-handler (:drag-handler shape)
        prev-handler (:prev-handler shape)
        last-command (last (:content shape))
        selected false
        last-p (last points)
        handlers (ugp/extract-handlers (:content shape))
        handlers (if (and prev-handler (not drag-handler))
                   (conj handlers {:point last-p :prev prev-handler})
                   handlers)
        ]

    [:g.path-editor
     (when (and (:preview shape) (not (:drag-handler shape)))
       [:*
        [:path {:style {:fill "transparent"
                        :stroke "#DB00FF"
                        :stroke-width (/ 1 zoom)}
                :d (ugp/content->path [{:command :move-to
                                        :params {:x (:x last-p)
                                                 :y (:y last-p)}}
                                       (:preview shape)])}]
        [:circle
         {:cx (-> shape :preview :params :x)
          :cy (-> shape :preview :params :y)
          :r (/ 3 zoom)
          :style {:stroke-width (/ 1 zoom)
                  :stroke "#DB00FF"
                  :fill "#FFFFFF"}}]])

     (for [{:keys [point prev next]} handlers]
       [:*
        [:& path-handler {:point point
                          :handler prev
                          :zoom zoom
                          :type :prev
                          :selected false}]
        [:& path-handler {:point point
                          :handler next
                          :zoom zoom
                          :type :next
                          :selected false}]])

     (when drag-handler
       [:*
        (when (not= :move-to (:command last-command))
          [:& path-handler {:point last-p
                            :handler (ugp/opposite-handler last-p drag-handler)
                            :zoom zoom
                            :type :drag-opposite
                            :selected false}])
        [:& path-handler {:point last-p
                          :handler drag-handler
                          :zoom zoom
                          :type :drag
                          :selected false}]])

     (for [{:keys [x y] :as point} points]
       [:circle
        {:cx x
         :cy y
         :r (/ 3 zoom)
         :style {:stroke-width (/ 1 zoom)
                 :stroke (if selected "#000000" "#1FDEA7")
                 :fill (if selected "#1FDEA7" "#FFFFFF")}
         }])]))
