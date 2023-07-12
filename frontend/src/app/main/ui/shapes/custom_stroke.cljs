;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.custom-stroke
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.geom.shapes.text :as gst]
   [app.common.pages.helpers :as cph]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn add-props
  [props new-props]
  (-> props
      (obj/merge (clj->js new-props))))

(defn add-style
  [props new-style]
  (let [old-style (obj/get props "style")
        style (obj/merge old-style (clj->js new-style))]
    (-> props (obj/merge #js {:style style}))))

(mf/defc inner-stroke-clip-path
  [{:keys [shape render-id index]}]
  (let [suffix (if index (str "-" index) "")
        clip-id (str "inner-stroke-" render-id "-" (:id shape) suffix)
        shape-id (str "stroke-shape-" render-id "-" (:id shape) suffix)]
    [:> "clipPath" #js {:id clip-id}
     [:use {:href (str "#" shape-id)}]]))

(mf/defc outer-stroke-mask
  [{:keys [shape stroke render-id index]}]
  (let [suffix (if index (str "-" index) "")
        stroke-mask-id (str "outer-stroke-" render-id "-" (:id shape) suffix)
        shape-id (str "stroke-shape-" render-id "-" (:id shape) suffix)
        stroke-width (case (:stroke-alignment stroke :center)
                       :center (/ (:stroke-width stroke 0) 2)
                       :outer (:stroke-width stroke 0)
                       0)
        margin (gsb/shape-stroke-margin stroke stroke-width)

        selrect
        (if (cph/text-shape? shape)
          (gst/shape->rect shape)
          (grc/points->rect (:points shape)))

        bounding-box
        (-> selrect
            (update :x - (+ stroke-width margin))
            (update :y - (+ stroke-width margin))
            (update :width + (* 2 (+ stroke-width margin)))
            (update :height + (* 2 (+ stroke-width margin)))
            (grc/update-rect :position))]

    [:mask {:id stroke-mask-id
            :x (:x bounding-box)
            :y (:y bounding-box)
            :width (:width bounding-box)
            :height (:height bounding-box)
            :maskUnits "userSpaceOnUse"}
     [:use {:href (str "#" shape-id)
            :style #js {:fill "none" :stroke "white" :strokeWidth (* stroke-width 2)}}]

     [:use {:href (str "#" shape-id)
            :style #js {:fill "black"
                        :stroke "none"}}]]))

(mf/defc cap-markers
  [{:keys [stroke render-id index]}]
  (let [marker-id-prefix (str "marker-" render-id)
        cap-start (:stroke-cap-start stroke)
        cap-end   (:stroke-cap-end stroke)

        stroke-color (if (:stroke-color-gradient stroke)
                       (str/format "url(#%s)" (str "stroke-color-gradient_" render-id "_" index))
                       (:stroke-color stroke))

        stroke-opacity (when-not (:stroke-color-gradient stroke)
                         (:stroke-opacity stroke))]

    [:*
      (when (or (= cap-start :line-arrow) (= cap-end :line-arrow))
        [:marker {:id (str marker-id-prefix "-line-arrow")
                  :viewBox "0 0 3 6"
                  :refX "2"
                  :refY "3"
                  :markerWidth "8.5"
                  :markerHeight "8.5"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:path {:d "M 0.5 0.5 L 3 3 L 0.5 5.5 L 0 5 L 2 3 L 0 1 z"}]])

      (when (or (= cap-start :triangle-arrow) (= cap-end :triangle-arrow))
        [:marker {:id (str marker-id-prefix "-triangle-arrow")
                  :viewBox "0 0 3 6"
                  :refX "2"
                  :refY "3"
                  :markerWidth "8.5"
                  :markerHeight "8.5"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:path {:d "M 0 0 L 3 3 L 0 6 z"}]])

      (when (or (= cap-start :square-marker) (= cap-end :square-marker))
        [:marker {:id (str marker-id-prefix "-square-marker")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "4.2426" ;; diagonal length of a 3x3 square
                  :markerHeight "4.2426"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:rect {:x 0 :y 0 :width 6 :height 6}]])

      (when (or (= cap-start :circle-marker) (= cap-end :circle-marker))
        [:marker {:id (str marker-id-prefix "-circle-marker")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "4"
                  :markerHeight "4"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:circle {:cx "3" :cy "3" :r "3"}]])

      (when (or (= cap-start :diamond-marker) (= cap-end :diamond-marker))
        [:marker {:id (str marker-id-prefix "-diamond-marker")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "6"
                  :markerHeight "6"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:path {:d "M 3 0 L 6 3 L 3 6 L 0 3 z"}]])

      ;; If the user wants line caps but different in each end,
      ;; simulate it with markers.
      (when (and (or (= cap-start :round) (= cap-end :round))
                 (not= cap-start cap-end))
        [:marker {:id (str marker-id-prefix "-round")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "6"
                  :markerHeight "6"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:path {:d "M 3 2.5 A 0.5 0.5 0 0 1 3 3.5 "}]])

      (when (and (or (= cap-start :square) (= cap-end :square))
                 (not= cap-start cap-end))
        [:marker {:id (str marker-id-prefix "-square")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "6"
                  :markerHeight "6"
                  :orient "auto-start-reverse"
                  :fill stroke-color
                  :fillOpacity stroke-opacity}
         [:rect {:x 3 :y 2.5 :width 0.5 :height 1}]])]))

(mf/defc stroke-defs
  [{:keys [shape stroke render-id index]}]

  (let [open-path? (and (= :path (:type shape)) (gsh/open-path? shape))]
    [:*
     (cond (some? (:stroke-color-gradient stroke))
           (case (:type (:stroke-color-gradient stroke))
             :linear [:> grad/linear-gradient #js {:id (str (name :stroke-color-gradient) "_" render-id "_" index)
                                                   :gradient (:stroke-color-gradient stroke)
                                                   :shape shape}]
             :radial [:> grad/radial-gradient #js {:id (str (name :stroke-color-gradient) "_" render-id "_" index)
                                                   :gradient (:stroke-color-gradient stroke)
                                                   :shape shape}]))
     (cond
       (and (not open-path?)
            (= :inner (:stroke-alignment stroke :center))
            (> (:stroke-width stroke 0) 0))
       [:& inner-stroke-clip-path {:shape shape
                                   :render-id render-id
                                   :index index}]

       (and (not open-path?)
            (= :outer (:stroke-alignment stroke :center))
            (> (:stroke-width stroke 0) 0))
       [:& outer-stroke-mask {:shape shape
                              :stroke stroke
                              :render-id render-id
                              :index index}]

       (or (some? (:stroke-cap-start stroke))
           (some? (:stroke-cap-end stroke)))
       [:& cap-markers {:stroke stroke
                        :render-id render-id
                        :index index}])]))

;; Outer alignment: display the shape in two layers. One
;; without stroke (only fill), and another one only with stroke
;; at double width (transparent fill) and passed through a mask
;; that shows the whole shape, but hides the original shape
;; without stroke
(mf/defc outer-stroke
  {::mf/wrap-props false}
  [props]

  (let [render-id      (mf/use-ctx muc/render-id)
        child          (obj/get props "children")
        base-props     (obj/get child "props")
        elem-name      (obj/get child "type")
        shape          (obj/get props "shape")
        stroke         (obj/get props "stroke")
        index          (obj/get props "index")
        stroke-width   (:stroke-width stroke)

        suffix         (if index (str "-" index) "")
        stroke-mask-id (str "outer-stroke-" render-id "-" (:id shape) suffix)
        shape-id       (str "stroke-shape-" render-id "-" (:id shape) suffix)]

    [:g.outer-stroke-shape
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]
      [:> elem-name (-> (obj/clone base-props)
                        (obj/set! "id" shape-id)
                        (obj/set!
                         "style"
                         (-> (obj/get base-props "style")
                             (obj/clone)
                             (obj/without ["fill" "fillOpacity" "stroke" "strokeWidth" "strokeOpacity" "strokeStyle" "strokeDasharray"]))))]]

     [:use {:href (str "#" shape-id)
            :mask (str "url(#" stroke-mask-id ")")
            :style (-> (obj/get base-props "style")
                       (obj/clone)
                       (obj/set! "strokeWidth" (* stroke-width 2))
                       (obj/without ["fill" "fillOpacity"])
                       (obj/set! "fill" "none"))}]

     [:use {:href (str "#" shape-id)
            :style (-> (obj/get base-props "style")
                       (obj/clone)
                       (obj/set! "stroke" "none"))}]]))


;; Inner alignment: display the shape with double width stroke,
;; and clip the result with the original shape without stroke.
(mf/defc inner-stroke
  {::mf/wrap-props false}
  [props]
  (let [render-id  (mf/use-ctx muc/render-id)
        child      (obj/get props "children")
        base-props (obj/get child "props")
        elem-name  (obj/get child "type")
        shape      (obj/get props "shape")
        stroke     (obj/get props "stroke")
        index      (obj/get props "index")
        transform  (obj/get base-props "transform")

        stroke-width (:stroke-width stroke 0)

        suffix (if index (str "-" index) "")
        clip-id (str "inner-stroke-" render-id "-" (:id shape) suffix)
        shape-id (str "stroke-shape-" render-id "-" (:id shape) suffix)

        clip-path (str "url('#" clip-id "')")
        shape-props (-> base-props
                        (add-props {:id shape-id
                                    :transform nil})
                        (add-style {:strokeWidth (* stroke-width 2)}))]

    [:g.inner-stroke-shape {:transform transform}
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]
      [:> elem-name shape-props]]

     [:use {:href (str "#" shape-id)
            :clipPath clip-path}]]))

; The SVG standard does not implement yet the 'stroke-alignment'
; attribute, to define the position of the stroke relative to the
; stroke axis (inner, center, outer). Here we implement a patch to be
; able to draw the stroke in the three cases. See discussion at:
; https://stackoverflow.com/questions/7241393/can-you-control-how-an-svgs-stroke-width-is-drawn
(mf/defc shape-custom-stroke
  {::mf/wrap-props false}
  [props]

  (let [child  (obj/get props "children")
        shape  (obj/get props "shape")
        stroke (obj/get props "stroke")

        render-id (mf/use-ctx muc/render-id)
        index (obj/get props "index")
        stroke-width (:stroke-width stroke 0)
        stroke-style (:stroke-style stroke :none)
        stroke-position (:stroke-alignment stroke :center)
        has-stroke? (and (> stroke-width 0)
                         (not= stroke-style :none))
        closed? (or (not= :path (:type shape)) (not (gsh/open-path? shape)))
        inner?  (= :inner stroke-position)
        outer?  (= :outer stroke-position)]

    (cond
      (and has-stroke? inner? closed?)
      [:& inner-stroke {:shape shape :stroke stroke :index index}
       child]

      (and has-stroke? outer? closed?)
      [:& outer-stroke {:shape shape :stroke stroke :index index}
       child]

      :else
      [:g.stroke-shape
       [:defs
        [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]]
       child])))

(defn build-fill-props [shape child position render-id]
  (let [url-fill?    (or (some? (:fill-image shape))
                         (= :image (:type shape))
                         (> (count (:fills shape)) 1)
                         (some :fill-color-gradient (:fills shape)))

        props        (cond-> (obj/create)
                       (or
                        ;; There are any shadows
                        (and (seq (->> (:shadow shape) (remove :hidden))) (not (cph/frame-shape? shape)))
                        ;; There is a blur
                        (and (:blur shape) (-> shape :blur :hidden not) (not (cph/frame-shape? shape))))
                       (obj/set! "filter" (dm/fmt "url(#filter_%)" render-id)))

        svg-defs  (:svg-defs shape {})
        svg-attrs (:svg-attrs shape {})

        [svg-attrs svg-styles]
        (attrs/extract-svg-attrs render-id svg-defs svg-attrs)]

    (cond
      url-fill?
      (let [props (obj/set! props
                            "style"
                            (-> (obj/get child "props")
                                (obj/get "style")
                                (obj/clone)
                                (obj/without ["fill" "fillOpacity"])))]
        (obj/set! props "fill" (dm/fmt "url(#fill-%-%)" position render-id)))

      (and (some? svg-styles) (obj/contains? svg-styles "fill"))
      (let [style
            (-> (obj/get child "props")
                (obj/get "style")
                (obj/clone)
                (obj/set! "fill" (obj/get svg-styles "fill"))
                (obj/set! "fillOpacity" (obj/get svg-styles "fillOpacity")))]
        (-> props
            (obj/set! "style" style)))

      (and (some? svg-attrs) (empty? (:fills shape)))
      (let [style
            (-> (obj/get child "props")
                (obj/get "style")
                (obj/clone))

            style (-> style
                      (obj/set! "fill" (obj/get svg-attrs "fill"))
                      (obj/set! "fillOpacity" (obj/get svg-attrs "fillOpacity")))]
        (-> props
            (obj/set! "style" style)))

      (d/not-empty? (:fills shape))
      (let [fill-props
            (attrs/extract-fill-attrs (get-in shape [:fills 0]) render-id 0 (:type shape))

            style (-> (obj/get child "props")
                      (obj/get "style")
                      (obj/clone)
                      (obj/merge! (obj/get fill-props "style")))]

        (cond-> (obj/merge! props fill-props)
          (some? style)
          (obj/set! "style" style)))

      (and (= :path (:type shape)) (empty? (:fills shape)))
      (let [style
            (-> (obj/get child "props")
                (obj/get "style")
                (obj/clone)
                (obj/set! "fill" "none"))]
        (-> props
            (obj/set! "style" style)))

      :else
      (obj/create))))

(defn build-stroke-props [position child value render-id]
  (let [props (-> (obj/get child "props")
                  (obj/clone)
                  (obj/without ["fill" "fillOpacity"]))]
    (-> props
        (obj/set!
         "style"
         (-> (obj/get props "style")
             (obj/set! "fill" "none")
             (obj/set! "fillOpacity" "none")))
        (add-style (obj/get (attrs/extract-stroke-attrs value position render-id) "style")))))

(mf/defc shape-fills
  {::mf/wrap-props false}
  [props]
  (let [child      (obj/get props "children")
        shape      (obj/get props "shape")
        elem-name  (obj/get child "type")
        position   (or (obj/get props "position") 0)
        render-id  (or (obj/get props "render-id") (mf/use-ctx muc/render-id))
        fill-props (build-fill-props shape child position render-id)]
    [:g.fills {:id (dm/fmt "fills-%" (:id shape))}
     [:> elem-name (-> (obj/get child "props")
                       (obj/clone)
                       (obj/merge! fill-props))]]))

(mf/defc shape-strokes
  {::mf/wrap-props false}
  [props]
  (let [child     (obj/get props "children")
        shape     (obj/get props "shape")

        elem-name (obj/get child "type")
        render-id (or (obj/get props "render-id") (mf/use-ctx muc/render-id))
        stroke-id (dm/fmt "strokes-%" (:id shape))
        stroke-props (-> (obj/create)
                         (obj/set! "id" stroke-id)
                         (obj/set! "className" "strokes")
                         (cond->
                          ;; There is a blur
                          (and (:blur shape) (not (cph/frame-shape? shape)) (-> shape :blur :hidden not))
                          (obj/set! "filter" (dm/fmt "url(#filter_blur_%)" render-id))

                          ;; There are any shadows and no fills
                          (and (empty? (:fills shape)) (not (cph/frame-shape? shape)) (seq (->> (:shadow shape) (remove :hidden))))
                          (obj/set! "filter" (dm/fmt "url(#filter_%)" render-id))))]
    [:*
     (when
      (d/not-empty? (:strokes shape))
       [:> :g stroke-props
        (for [[index value] (-> (d/enumerate (:strokes shape)) reverse)]
          (let [props (build-stroke-props index child value render-id)]
            [:& shape-custom-stroke {:shape shape :stroke value :index index :key (dm/str index "-" stroke-id)}
             [:> elem-name props]]))])]))

(mf/defc shape-custom-strokes
  {::mf/wrap-props false}
  [props]
  (let [children  (obj/get props "children")
        shape     (obj/get props "shape")
        position  (obj/get props "position")
        render-id (obj/get props "render-id")]
    [:*
     [:& shape-fills {:shape shape :position position :render-id render-id} children]
     [:& shape-strokes {:shape shape :position position :render-id render-id} children]]))
