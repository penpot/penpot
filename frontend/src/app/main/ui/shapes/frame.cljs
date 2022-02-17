;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.main.ui.shapes.filters :as filters]
   [app.util.object :as obj]
   [debug :refer [debug?]]
   [rumext.alpha :as mf]))

(defn frame-clip-id
  [shape render-id]
  (dm/str "frame-clip-" (:id shape) "-" render-id))

(defn frame-clip-url
  [shape render-id]
  (when (= :frame (:type shape))
    (dm/str "url(#" (frame-clip-id shape render-id) ")")))

(mf/defc frame-clip-def
  [{:keys [shape render-id]}]
  (when (= :frame (:type shape))
    (let [{:keys [x y width height]} shape
          padding (filters/calculate-padding shape)]
      [:clipPath {:id (frame-clip-id shape render-id) :class "frame-clip"}
       [:rect {:x (- x padding) :y (- y padding) :width (+ width (* 2 padding)) :height (+ height (* 2 padding))}]])))

(mf/defc frame-thumbnail
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")]
    (when (:thumbnail shape)
      [:image.frame-thumbnail
       {:id (dm/str "thumbnail-" (:id shape))
        :xlinkHref (:thumbnail shape)
        :x (:x shape)
        :y (:y shape)
        :width (:width shape)
        :height (:height shape)
        ;; DEBUG
        :style {:filter (when (debug? :thumbnails) "sepia(1)")}}])))

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]
    (let [childs     (unchecked-get props "childs")
          shape      (unchecked-get props "shape")
          {:keys [x y width height]} shape

          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x x
                          :y y
                          :width width
                          :height height
                          :className "frame-background"}))]

      [:*
       [:& shape-custom-strokes {:shape shape}
        [:> :rect props]]

       (for [item childs]
         [:& shape-wrapper {:shape item
                            :key (dm/str (:id item))}])])))

