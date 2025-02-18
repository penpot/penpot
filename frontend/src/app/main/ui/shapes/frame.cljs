;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.frame
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.types.shape.layout :as ctl]
   [app.config :as cf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-fills shape-strokes]]
   [app.main.ui.shapes.filters :as filters]
   [app.util.debug :as dbg]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- frame-clip-id
  [shape render-id]
  (dm/str "frame-clip-" (dm/get-prop shape :id) "-" render-id))

(defn- frame-clip-url
  [shape render-id]
  (dm/str "url(#" (frame-clip-id shape render-id) ")"))

(mf/defc frame-clip-def
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")]
    (when (and ^boolean (cfh/frame-shape? shape)
               (not ^boolean (:show-content shape)))

      (let [render-id (unchecked-get props "render-id")
            x         (dm/get-prop shape :x)
            y         (dm/get-prop shape :y)
            w         (dm/get-prop shape :width)
            h         (dm/get-prop shape :height)
            t         (gsh/transform-str shape)

            props     (mf/with-memo [shape]
                        (-> #js {}
                            (attrs/add-border-props! shape)
                            (obj/merge! #js {:x x :y y :width w :height h :transform t})))

            path?     (some? (.-d props))]

        [:clipPath {:id (frame-clip-id shape render-id)
                    :class "frame-clip frame-clip-def"}
         (if ^boolean path?
           [:> :path props]
           [:> :rect props])]))))

;; Wrapper around the frame that will handle things such as strokes and other properties
;; we wrap the proper frames and also the thumbnails
(mf/defc frame-container
  {::mf/wrap-props false}
  [props]
  (let [shape         (unchecked-get props "shape")
        children      (unchecked-get props "children")

        render-id     (mf/use-ctx muc/render-id)

        filter-id-blur     (dm/fmt "filter-blur-%" render-id)
        filter-id-shadows  (dm/fmt "filter-shadow-%" render-id)
        filter-str-blur    (filters/filter-str filter-id-blur (dissoc shape :shadow))
        filter-str-shadows (filters/filter-str filter-id-shadows (dissoc shape :blur))

        x             (dm/get-prop shape :x)
        y             (dm/get-prop shape :y)
        w             (dm/get-prop shape :width)
        h             (dm/get-prop shape :height)
        opacity       (dm/get-prop shape :opacity)
        transform     (gsh/transform-str shape)

        show-content? (get shape :show-content)

        props         (mf/with-memo [shape]
                        (-> #js {}
                            (attrs/add-border-props! shape)
                            (obj/merge!
                             #js {:x x
                                  :y y
                                  :width w
                                  :height h
                                  :transform transform
                                  :className "frame-background"})))
        path?         (some? (.-d props))]

    ;; We need to separate blur from shadows because the blur is applied to the strokes
    ;; while the shadows have to be placed *under* the stroke (for example, the inner shadows)
    ;; and the shadows needs to be applied only to the content (without the stroke)
    [:g.frame-container-wrapper {:opacity opacity}
     [:g.frame-container-blur {:filter filter-str-blur}
      [:defs
       [:& filters/filters {:shape (dissoc shape :blur) :filter-id filter-id-shadows}]
       [:& filters/filters {:shape (assoc shape :shadow []) :filter-id filter-id-blur}]]

     ;; This need to be separated in two layers so the clip doesn't affect the shadow filters
     ;; otherwise the shadow will be clipped and not visible
      [:g.frame-container-shadows {:filter filter-str-shadows}
       [:g {:clip-path (when-not ^boolean show-content? (frame-clip-url shape render-id))
        ;; A frame sets back normal fill behavior (default
        ;; transparent). It may have been changed to default black
        ;; if a shape coming from an imported SVG file is
        ;; rendered. See main.ui.shapes.attrs/add-style-attrs.
            :fill "none"}

        [:& shape-fills {:shape shape}
         (if ^boolean path?
           [:> :path props]
           [:> :rect props])]
        children]]

      [:& shape-strokes {:shape shape}
       (if ^boolean path?
         [:> :path props]
         [:> :rect props])]]]))

(mf/defc frame-thumbnail-image
  {::mf/wrap-props false}
  [props]
  (let [shape    (unchecked-get props "shape")
        bounds   (unchecked-get props "bounds")

        shape-id (dm/get-prop shape :id)
        points   (dm/get-prop shape :points)

        bounds   (mf/with-memo [bounds points]
                   (or bounds (gsb/get-frame-bounds shape)))

        thumb    (cf/resolve-media (:thumbnail-id shape))

        debug?   (dbg/enabled? :thumbnails)
        safari?  (cf/check-browser? :safari)

        ;; FIXME: ensure bounds is always a rect instance and
        ;; dm/get-prop for static attr access
        bx       (:x bounds)
        by       (:y bounds)
        bh       (:height bounds)
        bw       (:width bounds)]

    [:*
     [:image.frame-thumbnail
      {:id (dm/str "thumbnail-" shape-id)
       :href thumb
       :x bx
       :y by
       :width bw
       :height bh
       :decoding "async"
       :style {:filter (when (and (not ^boolean safari?) ^boolean debug?) "sepia(1)")}}]

     ;; Safari don't support filters so instead we add a rectangle around the thumbnail
     (when (and ^boolean safari? ^boolean debug?)
       [:rect {:x (+ bx 4)
               :y (+ by 4)
               :width (- bw 8)
               :height (- bh 8)
               :stroke "red"
               :stroke-width 2}])]))

(mf/defc frame-thumbnail
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")]
    (when ^boolean (:thumbnail-id shape)
      [:> frame-container props
       [:> frame-thumbnail-image props]])))

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]
    (let [shape         (unchecked-get props "shape")
          childs        (unchecked-get props "childs")
          reverse?      (and (ctl/flex-layout? shape) (ctl/reverse? shape))
          childs        (cond-> childs
                          (ctl/any-layout? shape)
                          (ctl/sort-layout-children-z-index reverse?))]

      [:> frame-container props
       [:g.frame-children
        (for [item childs]
          (let [id (dm/get-prop item :id)]
            (when (some? id)
              [:& shape-wrapper {:key (dm/str id) :shape item}])))]])))

