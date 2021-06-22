;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.frame
  (:require
   [app.main.ui.shapes.attrs :as attrs]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]
    (let [childs     (unchecked-get props "childs")
          shape      (unchecked-get props "shape")
          {:keys [width height]} shape

          has-background? (or (some? (:fill-color shape))
                              (some? (:fill-color-gradient shape)))
          has-stroke? (not= :none (:stroke-style shape))

          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x 0
                          :y 0
                          :width width
                          :height height
                          :className "frame-background"}))]
      [:*
       (when (or has-background? has-stroke?)
         [:> :rect props])
       (for [item childs]
         [:& shape-wrapper {:frame shape
                            :shape item
                            :key (:id item)}])])))

