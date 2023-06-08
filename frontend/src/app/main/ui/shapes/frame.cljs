;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.config :as cf]
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
  (when (and (= :frame (:type shape)) (not (:show-content shape)))
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
      [:clipPath.frame-clip-def {:id (frame-clip-id shape render-id) :class "frame-clip"}
       (if ^boolean path?
         [:> :path props]
         [:> :rect props])])))

;; Wrapper around the frame that will handle things such as strokes and other properties
;; we wrap the proper frames and also the thumbnails
(mf/defc frame-container
  {::mf/wrap-props false}
  [props]

  (let [shape     (unchecked-get props "shape")
        children  (unchecked-get props "children")

        {:keys [x y width height show-content]} shape
        transform (gsh/transform-str shape)

        render-id (mf/use-ctx muc/render-id)

        props     (-> (attrs/extract-style-attrs shape render-id)
                      (obj/merge!
                       #js {:x x
                            :y y
                            :transform transform
                            :width width
                            :height height
                            :className "frame-background"}))
        path? (some? (.-d props))]
    [:*
     [:g {:clip-path (when (not show-content) (frame-clip-url shape render-id))
          :fill "none"}   ;; A frame sets back normal fill behavior (default transparent). It may have
                          ;; been changed to default black if a shape coming from an imported SVG file
                          ;; is rendered. See main.ui.shapes.attrs/add-style-attrs.
      [:& frame-clip-def {:shape shape :render-id render-id}]

      [:& shape-fills {:shape shape}
       (if ^boolean path?
         [:> :path props]
         [:> :rect props])]

      children]

     [:& shape-strokes {:shape shape}
      (if ^boolean path?
        [:> :path props]
        [:> :rect props])]]))


(mf/defc frame-thumbnail-image
  {::mf/wrap-props false}
  [props]
  (let [shape    (unchecked-get props "shape")
        bounds   (or (unchecked-get props "bounds")
                     (gsh/points->selrect (:points shape)))

        shape-id (:id shape)
        thumb    (:thumbnail shape)

        debug?   (debug? :thumbnails)
        safari?  (cf/check-browser? :safari)]

    [:*
     [:image.frame-thumbnail
      {:id (dm/str "thumbnail-" shape-id)
       :href thumb
       :decoding "async"
       :x (:x bounds)
       :y (:y bounds)
       :width (:width bounds)
       :height (:height bounds)
       :style {:filter (when (and (not ^boolean safari?) ^boolean debug?) "sepia(1)")}}]

     ;; Safari don't support filters so instead we add a rectangle around the thumbnail
     (when (and ^boolean safari? ^boolean debug?)
       [:rect {:x (+ (:x bounds) 4)
               :y (+ (:y bounds) 4)
               :width (- (:width bounds) 8)
               :height (- (:height bounds) 8)
               :stroke "red"
               :stroke-width 2}])]))

(mf/defc frame-thumbnail
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")]
    (when ^boolean (:thumbnail shape)
      [:> frame-container props
       [:> frame-thumbnail-image props]])))

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]
    (let [shape  (unchecked-get props "shape")
          childs (unchecked-get props "childs")
          childs (cond-> childs
                   (ctl/any-layout? shape)
                   (cph/sort-layout-children-z-index))]
      [:> frame-container props
       [:g.frame-children {:opacity (:opacity shape)}
        (for [item childs]
          [:& shape-wrapper {:key (dm/str (:id item)) :shape item}])]])))

