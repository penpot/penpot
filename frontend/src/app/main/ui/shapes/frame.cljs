;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-fills shape-strokes]]
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
          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x x
                          :y y
                          :width width
                          :height height}))
          path? (some? (.-d props))]
      [:clipPath {:id (frame-clip-id shape render-id) :class "frame-clip"}
       (if path?
         [:> :path props]
         [:> :rect props])])))

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

          transform (gsh/transform-matrix shape)

          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x x
                          :y y
                          :transform (str transform)
                          :width width
                          :height height
                          :className "frame-background"}))
          path? (some? (.-d props))
          render-id (mf/use-ctx muc/render-ctx)]

      [:*
       [:g {:clip-path (frame-clip-url shape render-id)}
        [:*
         [:& shape-fills {:shape shape}
          (if path?
            [:> :path props]
            [:> :rect props])]

         (for [item childs]
           [:& shape-wrapper {:shape item
                              :key (dm/str (:id item))}])
         [:& shape-strokes {:shape shape}
          (if path?
            [:> :path props]
            [:> :rect props])]]]])))

