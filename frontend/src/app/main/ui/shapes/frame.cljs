;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.frame
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.main.ui.shapes.attrs :as attrs]
   [app.common.geom.shapes :as geom]
   [app.util.object :as obj]))

(def frame-default-props {:fill-color "#ffffff"})

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]
    (let [childs (unchecked-get props "childs")
          shape  (unchecked-get props "shape")
          {:keys [id x y width height]} shape

          props (-> (merge frame-default-props shape)
                    (attrs/extract-style-attrs)
                    (obj/merge!
                     #js {:x 0
                          :y 0
                          :id (str "shape-" id)
                          :width width
                          :height height}))]
      [:svg {:x x :y y :width width :height height
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :xmlns "http://www.w3.org/2000/svg"}
       [:> "rect" props]
       (for [[i item] (d/enumerate childs)]
         [:& shape-wrapper {:frame shape
                            :shape item
                            :key (:id item)}])])))

