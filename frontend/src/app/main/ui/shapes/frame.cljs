;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-fills shape-strokes]]
   [app.util.object :as obj]
   [debug :refer [debug?]]
   [rumext.v2 :as mf]))

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
          transform (gsh/transform-str shape)
          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x x
                          :y y
                          :width width
                          :height height
                          :transform transform}))
          path? (some? (.-d props))]
      [:clipPath {:id (frame-clip-id shape render-id) :class "frame-clip"}
       (if path?
         [:> :path props]
         [:> :rect props])])))

;; Wrapper around the frame that will handle things such as strokes and other properties
;; we wrap the proper frames and also the thumbnails
(mf/defc frame-container
  {::mf/wrap-props false}
  [props]

  (let [shape (obj/get props "shape")
        children (obj/get props "children")

        {:keys [x y width height show-content]} shape
        transform (gsh/transform-str shape)
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:x x
                        :y y
                        :transform transform
                        :width width
                        :height height
                        :className "frame-background"}))
        path? (some? (.-d props))
        render-id (mf/use-ctx muc/render-id)]

    [:*
     [:g {:clip-path (when (not show-content) (frame-clip-url shape render-id))}
      (when (not show-content)
        [:& frame-clip-def {:shape shape :render-id render-id}])

      [:& shape-fills {:shape shape}
       (if path?
         [:> :path props]
         [:> :rect props])]

      children]

     [:& shape-strokes {:shape shape}
      (if path?
        [:> :path props]
        [:> :rect props])]]))


(mf/defc frame-thumbnail-image
  {::mf/wrap-props false}
  [props]

  (let [shape (obj/get props "shape")
        bounds (or (obj/get props "bounds") (gsh/points->selrect (:points shape)))]

    (when (:thumbnail shape)
      [:image.frame-thumbnail
       {:id (dm/str "thumbnail-" (:id shape))
        :href (:thumbnail shape)
        :x (:x bounds)
        :y (:y bounds)
        :width (:width bounds)
        :height (:height bounds)
        ;; DEBUG
        :style {:filter (when (debug? :thumbnails) "sepia(1)")}}])))

(mf/defc frame-thumbnail
  {::mf/wrap-props false}
  [props]
  (let [shape (obj/get props "shape")]
    (when (:thumbnail shape)
      [:> frame-container props
       [:> frame-thumbnail-image props]])))

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]

    (let [childs     (unchecked-get props "childs")]
      [:> frame-container props
       [:g.frame-children
        (for [item childs]
          [:& shape-wrapper {:key (dm/str (:id item)) :shape item}])]])))

