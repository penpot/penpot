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

(mf/defc inner-stroke-clip-path
  {::mf/wrap-props false}
  [{:keys [shape render-id index]}]
  (let [shape-id (dm/get-prop shape :id)
        suffix   (if (some? index) (dm/str "-" index) "")
        clip-id  (dm/str "inner-stroke-" render-id "-" shape-id suffix)
        href     (dm/str "#stroke-shape-" render-id "-" shape-id suffix)]
    [:> "clipPath" #js {:id clip-id}
     [:use {:href href}]]))

(mf/defc outer-stroke-mask
  {::mf/wrap-props false}
  [{:keys [shape stroke render-id index]}]
  (let [shape-id       (dm/get-prop shape :id)
        suffix         (if (some? index) (dm/str "-" index) "")
        mask-id        (dm/str "outer-stroke-" render-id "-" shape-id suffix)
        shape-id       (dm/str "stroke-shape-" render-id "-" shape-id suffix)
        href           (dm/str "#" shape-id)

        stroke-width   (case (:stroke-alignment stroke :center)
                         :center (/ (:stroke-width stroke 0) 2)
                         :outer (:stroke-width stroke 0)
                         0)
        margin         (gsb/shape-stroke-margin stroke stroke-width)

        ;; NOTE: for performance reasons we may can delimit a bit the
        ;; dependencies to really useful shape attrs instead of using
        ;; the shepe as-is.
        selrect       (mf/with-memo [shape]
                        (if (cph/text-shape? shape)
                          (gst/shape->rect shape)
                          (grc/points->rect (:points shape))))

        stroke-margin (+ stroke-width margin)

        x             (- (dm/get-prop selrect :x) stroke-margin)
        y             (- (dm/get-prop selrect :y) stroke-margin)
        w             (+ (dm/get-prop selrect :width) (* 2 stroke-margin))
        h             (+ (dm/get-prop selrect :height) (* 2 stroke-margin))]

    [:mask {:id mask-id
            :x x
            :y y
            :width w
            :height h
            :maskUnits "userSpaceOnUse"}
     [:use
      {:href href
       :style {:fill "none"
               :stroke "white"
               :strokeWidth (* stroke-width 2)}}]

     [:use
      {:href href
       :style {:fill "black"
               :stroke "none"}}]]))

(mf/defc cap-markers
  {::mf/wrap-props false}
  [{:keys [stroke render-id index]}]
  (let [id-prefix (dm/str "marker-" render-id)

        gradient  (:stroke-color-gradient stroke)
        cap-start (:stroke-cap-start stroke)
        cap-end   (:stroke-cap-end stroke)

        color     (if (some? gradient)
                    (str/ffmt "url(#stroke-color-gradient_%s_%s)" render-id index)
                    (:stroke-color stroke))

        opacity   (when-not (some? gradient)
                    (:stroke-opacity stroke))]

    [:*
      (when (or (= cap-start :line-arrow)
                (= cap-end :line-arrow))
        [:marker {:id (dm/str id-prefix "-line-arrow")
                  :viewBox "0 0 3 6"
                  :refX "2"
                  :refY "3"
                  :markerWidth "8.5"
                  :markerHeight "8.5"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:path {:d "M 0.5 0.5 L 3 3 L 0.5 5.5 L 0 5 L 2 3 L 0 1 z"}]])

      (when (or (= cap-start :triangle-arrow)
                (= cap-end :triangle-arrow))
        [:marker {:id (dm/str id-prefix "-triangle-arrow")
                  :viewBox "0 0 3 6"
                  :refX "2"
                  :refY "3"
                  :markerWidth "8.5"
                  :markerHeight "8.5"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:path {:d "M 0 0 L 3 3 L 0 6 z"}]])

      (when (or (= cap-start :square-marker)
                (= cap-end :square-marker))
        [:marker {:id (dm/str id-prefix "-square-marker")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "4.2426" ;; diagonal length of a 3x3 square
                  :markerHeight "4.2426"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:rect {:x 0 :y 0 :width 6 :height 6}]])

      (when (or (= cap-start :circle-marker)
                (= cap-end :circle-marker))
        [:marker {:id (dm/str id-prefix "-circle-marker")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "4"
                  :markerHeight "4"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:circle {:cx "3" :cy "3" :r "3"}]])

      (when (or (= cap-start :diamond-marker)
                (= cap-end :diamond-marker))
        [:marker {:id (dm/str id-prefix "-diamond-marker")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "6"
                  :markerHeight "6"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:path {:d "M 3 0 L 6 3 L 3 6 L 0 3 z"}]])

      ;; If the user wants line caps but different in each end,
      ;; simulate it with markers.
      (when (and (or (= cap-start :round)
                     (= cap-end :round))
                 (not= cap-start cap-end))
        [:marker {:id (dm/str id-prefix "-round")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "6"
                  :markerHeight "6"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:path {:d "M 3 2.5 A 0.5 0.5 0 0 1 3 3.5 "}]])

      (when (and (or (= cap-start :square)
                     (= cap-end :square))
                 (not= cap-start cap-end))
        [:marker {:id (dm/str id-prefix "-square")
                  :viewBox "0 0 6 6"
                  :refX "3"
                  :refY "3"
                  :markerWidth "6"
                  :markerHeight "6"
                  :orient "auto-start-reverse"
                  :fill color
                  :fillOpacity opacity}
         [:rect {:x 3 :y 2.5 :width 0.5 :height 1}]])]))

(mf/defc stroke-defs
  {::mf/wrap-props false}
  [{:keys [shape stroke render-id index]}]
  (let [open-path? (and ^boolean (cph/path-shape? shape)
                        ^boolean (gsh/open-path? shape))
        gradient   (:stroke-color-gradient stroke)
        alignment  (:stroke-alignment stroke :center)
        width      (:stroke-width stroke 0)

        props      #js {:id (dm/str "stroke-color-gradient_" render-id "_" index)
                        :gradient gradient
                        :shape shape}]
    [:*
     (when (some? gradient)
       (case (:type gradient)
         :linear [:> grad/linear-gradient props]
         :radial [:> grad/radial-gradient props]))

     (cond
       (and (not open-path?)
            (= :inner alignment)
            (> width 0))
       [:& inner-stroke-clip-path {:shape shape
                                   :render-id render-id
                                   :index index}]

       (and (not open-path?)
            (= :outer alignment)
            (> width 0))
       [:& outer-stroke-mask {:shape shape
                              :stroke stroke
                              :render-id render-id
                              :index index}]

       (or (some? (:stroke-cap-start stroke))
           (some? (:stroke-cap-end stroke)))
       [:& cap-markers {:stroke stroke
                        :render-id render-id
                        :index index}])]))

;; Outer alignment: display the shape in two layers. One without
;; stroke (only fill), and another one only with stroke at double
;; width (transparent fill) and passed through a mask that shows the
;; whole shape, but hides the original shape without stroke

(mf/defc outer-stroke
  {::mf/wrap-props false}
  [props]

  (let [child        (unchecked-get props "children")
        shape        (unchecked-get props "shape")
        stroke       (unchecked-get props "stroke")
        index        (unchecked-get props "index")

        shape-id     (dm/get-prop shape :id)
        render-id    (mf/use-ctx muc/render-id)

        props        (obj/get child "props")
        style        (obj/get props "style")

        stroke-width (:stroke-width stroke 0)

        suffix       (if (some? index) (dm/str "-" index) "")
        mask-id      (dm/str "outer-stroke-" render-id "-" shape-id suffix)
        shape-id     (dm/str "stroke-shape-" render-id "-" shape-id suffix)
        href         (dm/str "#" shape-id)]

    [:g.outer-stroke-shape
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]
      (let [type  (obj/get child "type")
            style (-> (obj/clone style)
                      (obj/unset! "fill")
                      (obj/unset! "fillOpacity")
                      (obj/unset! "stroke")
                      (obj/unset! "strokeWidth")
                      (obj/unset! "strokeOpacity")
                      (obj/unset! "strokeStyle")
                      (obj/unset! "strokeDasharray"))
            props (-> (obj/clone props)
                      (obj/set! "id" shape-id)
                      (obj/set! "style" style))]

        [:> type props])]

     [:use {:href href
            :mask (dm/str "url(#" mask-id ")")
            :style (-> (obj/clone style)
                       (obj/set! "strokeWidth" (* stroke-width 2))
                       (obj/set! "fill" "none")
                       (obj/unset! "fillOpacity"))}]

     [:use {:href href
            :style (-> (obj/clone style)
                       (obj/set! "stroke" "none"))}]]))


;; Inner alignment: display the shape with double width stroke, and
;; clip the result with the original shape without stroke.

(mf/defc inner-stroke
  {::mf/wrap-props false}
  [props]
  (let [child        (unchecked-get props "children")
        shape        (unchecked-get props "shape")
        stroke       (unchecked-get props "stroke")
        index        (unchecked-get props "index")

        shape-id     (dm/get-prop shape :id)
        render-id    (mf/use-ctx muc/render-id)

        type         (obj/get child "type")

        props        (-> (obj/get child "props") obj/clone)
        ;; FIXME: check if style need to be cloned
        style        (-> (obj/get props "style") obj/clone)
        transform    (obj/get props "transform")

        stroke-width (:stroke-width stroke 0)

        suffix       (if (some? index) (dm/str "-" index) "")
        clip-id      (dm/str "inner-stroke-" render-id "-" shape-id suffix)
        shape-id     (dm/str "stroke-shape-" render-id "-" shape-id suffix)
        clip-path    (dm/str "url('#" clip-id "')")

        style        (obj/set! style "strokeWidth" (* stroke-width 2))

        props        (-> props
                         (obj/set! "id" (dm/str shape-id))
                         (obj/set! "style" style)
                         (obj/unset! "transform"))]

    [:g.inner-stroke-shape
     {:transform transform}
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]
      [:> type props]]

     [:use {:href (dm/str "#" shape-id)
            :clipPath clip-path}]]))

;; The SVG standard does not implement yet the 'stroke-alignment'
;; attribute, to define the position of the stroke relative to the
;; stroke axis (inner, center, outer). Here we implement a patch to be
;; able to draw the stroke in the three cases. See discussion at:
;; https://stackoverflow.com/questions/7241393/can-you-control-how-an-svgs-stroke-width-is-drawn

(mf/defc shape-custom-stroke
  {::mf/wrap-props false}
  [props]
  (let [child           (unchecked-get props "children")
        shape           (unchecked-get props "shape")
        stroke          (unchecked-get props "stroke")
        index           (unchecked-get props "index")

        render-id       (mf/use-ctx muc/render-id)

        stroke-width    (:stroke-width stroke 0)
        stroke-style    (:stroke-style stroke :none)
        stroke-position (:stroke-alignment stroke :center)

        has-stroke?     (and (> stroke-width 0)
                             (not= stroke-style :none))
        closed?         (or (not ^boolean (cph/path-shape? shape))
                            (not ^boolean (gsh/open-path? shape)))
        inner?          (= :inner stroke-position)
        outer?          (= :outer stroke-position)]

    (cond
      (and has-stroke? inner? closed?)
      [:& inner-stroke {:shape shape :stroke stroke :index index} child]

      (and has-stroke? outer? closed?)
      [:& outer-stroke {:shape shape :stroke stroke :index index} child]

      :else
      [:g.stroke-shape
       [:defs
        [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]]
       child])))

(defn- build-fill-element
  [shape child position render-id]
  (let [shape-fills  (get shape :fills)
        shape-shadow (get shape :shadow)
        shape-blur   (get shape :blur)

        type         (obj/get child "type")
        props        (-> (obj/get child "props")
                         (obj/clone))
        style        (-> (obj/get props "style")
                         (obj/clone))

        url-fill?    (or ^boolean (some? (:fill-image shape))
                         ^boolean (cph/image-shape? shape)
                         ^boolean (> (count shape-fills) 1)
                         ^boolean (some? (some :fill-color-gradient shape-fills)))

        props        (if (cph/frame-shape? shape)
                       props
                       (if (or (some? (->> shape-shadow (remove :hidden) seq))
                               (not ^boolean (:hidden shape-blur)))
                         (obj/set! props "filter" (dm/fmt "url(#filter_%)" render-id))
                         props))

        svg-attrs    (attrs/get-svg-attrs shape render-id)
        svg-styles   (get svg-attrs :style {})]

    (cond
      ^boolean url-fill?
      (do
        (obj/unset! style "fill")
        (obj/unset! style "fillOpacity")
        (obj/set! props "fill" (dm/fmt "url(#fill-%-%)" position render-id)))

      (and ^boolean (or (contains? svg-styles :fill)
                        (contains? svg-styles :fillOpacity))
           ^boolean (obj/contains? svg-styles "fill"))

      (let [fill    (get svg-styles :fill)
            opacity (get svg-styles :fillOpacity)]
        (when (some? fill)
          (obj/set! style "fill" fill))
        (when (some? opacity)
          (obj/set! style "fillOpacity" opacity)))

      (and ^boolean (or (contains? svg-attrs :fill)
                        (contains? svg-attrs :fillOpacity))
           ^boolean (empty? shape-fills))
      (let [fill    (get svg-attrs :fill)
            opacity (get svg-attrs :fillOpacity)]
        (when (some? fill)
          (obj/set! style "fill" fill))
        (when (some? opacity)
          (obj/set! style "fillOpacity" opacity)))

      ^boolean (d/not-empty? shape-fills)
      (let [fill (nth shape-fills 0)]
        (obj/merge! style (attrs/get-fill-style fill render-id 0 (dm/get-prop shape :type))))

      (and ^boolean (cph/path-shape? shape)
           ^boolean (empty? shape-fills))
      (obj/set! style "fill" "none"))

    (let [props (obj/set! props "style" style)]
      (mf/html [:> type props]))))

(defn- build-stroke-element
  [child value position render-id]
  (let [props (obj/get child "props")
        type  (obj/get child "type")

        style (-> (obj/get props "style")
                  (obj/clone)
                  (obj/set! "fill" "none")
                  (obj/set! "fillOpacity" "none")
                  (obj/merge! (attrs/get-stroke-style value position render-id)))

        props (-> (obj/clone props)
                  (obj/unset! "fill")
                  (obj/unset! "fillOpacity")
                  (obj/set! "style" style))]

    (mf/html [:> type props])))

(mf/defc shape-fills
  {::mf/wrap-props false}
  [props]
  (let [child      (unchecked-get props "children")
        shape      (unchecked-get props "shape")

        shape-id   (dm/get-prop shape :id)

        position   (d/nilv (unchecked-get props "position") 0)

        render-id  (mf/use-ctx muc/render-id)
        render-id  (d/nilv (unchecked-get props "render-id") render-id)]

    [:g.fills {:id (dm/fmt "fills-%" shape-id)}
     (build-fill-element shape child position render-id)]))

(mf/defc shape-strokes
  {::mf/wrap-props false}
  [props]
  (let [child         (unchecked-get props "children")
        shape         (unchecked-get props "shape")

        shape-id      (dm/get-prop shape :id)

        render-id     (mf/use-ctx muc/render-id)
        render-id     (d/nilv (unchecked-get props "render-id") render-id)

        stroke-id     (dm/fmt "strokes-%" shape-id)

        shape-blur    (get shape :blur)
        shape-fills   (get shape :fills)
        shape-shadow  (get shape :shadow)
        shape-strokes (get shape :strokes)

        props         #js {:id stroke-id :className "strokes"}
        props         (if ^boolean (cph/frame-shape? shape)
                        props
                        (cond
                          (and (some? shape-blur)
                               (not ^boolean (:hidden shape-blur)))
                          (obj/set! props "filter" (dm/fmt "url(#filter_blur_%)" render-id))

                          (and (empty? shape-fills)
                               (some? (->> shape-shadow (remove :hidden) seq)))
                          (obj/set! props "filter" (dm/fmt "url(#filter_%)" render-id))))]


    (when (d/not-empty? shape-strokes)
      [:> :g props
       (for [[index value] (-> (d/enumerate shape-strokes) reverse)]
         [:& shape-custom-stroke {:shape shape
                                  :stroke value
                                  :index index
                                  :key (dm/str index "-" stroke-id)}
          (build-stroke-element child value index render-id)])])))

(mf/defc shape-custom-strokes
  {::mf/wrap-props false}
  [props]
  (let [children  (unchecked-get props "children")
        shape     (unchecked-get props "shape")
        position  (unchecked-get props "position")
        render-id (unchecked-get props "render-id")
        props     #js {:shape shape
                       :position position
                       :render-id render-id}]
    [:*
     [:> shape-fills props children]
     [:> shape-strokes props children]]))
