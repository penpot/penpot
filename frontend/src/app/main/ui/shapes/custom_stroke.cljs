;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.custom-stroke
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

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
  [{:keys [render-id]}]
  (let [clip-id (str "inner-stroke-" render-id)
        shape-id (str "stroke-shape-" render-id)]
    [:> "clipPath" #js {:id clip-id}
     [:use {:xlinkHref (str "#" shape-id)}]]))

(mf/defc outer-stroke-mask
  [{:keys [shape render-id]}]
  (let [stroke-mask-id (str "outer-stroke-" render-id)
        shape-id (str "stroke-shape-" render-id)
        stroke-width (case (:stroke-alignment shape :center)
                       :center (/ (:stroke-width shape 0) 2)
                       :outer (:stroke-width shape 0)
                       0)
        margin (gsh/shape-stroke-margin shape stroke-width)
        bounding-box (-> (gsh/points->selrect (:points shape))
                         (update :x - (+ stroke-width margin))
                         (update :y - (+ stroke-width margin))
                         (update :width + (* 2 (+ stroke-width margin)))
                         (update :height + (* 2 (+ stroke-width margin))))]
    [:mask {:id stroke-mask-id
            :x (:x bounding-box)
            :y (:y bounding-box)
            :width (:width bounding-box)
            :height (:height bounding-box)
            :maskUnits "userSpaceOnUse"}
     [:use {:xlinkHref (str "#" shape-id)
            :style #js {:fill "none" :stroke "white" :strokeWidth (* stroke-width 2)}}]

     [:use {:xlinkHref (str "#" shape-id)
            :style #js {:fill "black"}}]]))

(mf/defc cap-markers
  [{:keys [shape render-id]}]
  (let [marker-id-prefix (str "marker-" render-id)
        cap-start (:stroke-cap-start shape)
        cap-end   (:stroke-cap-end shape)

        stroke-color (if (:stroke-color-gradient shape)
                       (str/format "url(#%s)" (str "stroke-color-gradient_" render-id))
                       (:stroke-color shape))

        stroke-opacity (when-not (:stroke-color-gradient shape)
                         (:stroke-opacity shape))]

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
  [{:keys [shape render-id]}]

  (let [open-path? (and (= :path (:type shape)) (gsh/open-path? shape))]
    (cond
      (and (not open-path?)
           (= :inner (:stroke-alignment shape :center))
           (> (:stroke-width shape 0) 0))
      [:& inner-stroke-clip-path {:shape shape
                                  :render-id render-id}]

      (and (not open-path?)
           (= :outer (:stroke-alignment shape :center))
           (> (:stroke-width shape 0) 0))
      [:& outer-stroke-mask {:shape shape
                             :render-id render-id}]

      (or (some? (:stroke-cap-start shape))
          (some? (:stroke-cap-end shape)))
      [:& cap-markers {:shape shape
                       :render-id render-id}])))

;; Outer alignment: display the shape in two layers. One
;; without stroke (only fill), and another one only with stroke
;; at double width (transparent fill) and passed through a mask
;; that shows the whole shape, but hides the original shape
;; without stroke
(mf/defc outer-stroke
  {::mf/wrap-props false}
  [props]

  (let [render-id    (mf/use-ctx muc/render-ctx)
        child        (obj/get props "children")
        base-props   (obj/get child "props")
        elem-name    (obj/get child "type")
        stroke-mask-id (str "outer-stroke-" render-id)
        shape-id (str "stroke-shape-" render-id)

        style-str (->> (obj/get base-props "style")
                       (js->clj)
                       (mapv (fn [[k v]]
                               (-> (d/name k)
                                   (str/kebab)
                                   (str ":" v))))
                       (str/join ";"))]

    [:g.outer-stroke-shape
     [:defs
      [:> elem-name (-> (obj/clone base-props)
                        (obj/set! "id" shape-id)
                        (obj/set! "data-style" style-str)
                        (obj/without ["style"]))]]

     [:use {:xlinkHref (str "#" shape-id)
            :mask (str "url(#" stroke-mask-id ")")
            :style (-> (obj/get base-props "style")
                       (obj/clone)
                       (obj/update! "strokeWidth" * 2)
                       (obj/without ["fill" "fillOpacity"])
                       (obj/set! "fill" "none"))}]

     [:use {:xlinkHref (str "#" shape-id)
            :style (-> (obj/get base-props "style")
                       (obj/clone)
                       (obj/without ["stroke" "strokeWidth" "strokeOpacity" "strokeStyle" "strokeDasharray"]))}]]))


;; Inner alignment: display the shape with double width stroke,
;; and clip the result with the original shape without stroke.
(mf/defc inner-stroke
  {::mf/wrap-props false}
  [props]
  (let [render-id  (mf/use-ctx muc/render-ctx)
        child      (obj/get props "children")
        base-props (obj/get child "props")
        elem-name  (obj/get child "type")
        shape      (obj/get props "shape")
        transform  (obj/get base-props "transform")

        stroke-width (:stroke-width shape 0)

        clip-id (str "inner-stroke-" render-id)
        shape-id (str "stroke-shape-" render-id)

        clip-path (str "url('#" clip-id "')")
        shape-props (-> base-props
                        (add-props {:id shape-id
                                    :transform nil})
                        (add-style {:strokeWidth (* stroke-width 2)}))]

    [:g.inner-stroke-shape {:transform transform}
     [:defs
      [:> elem-name shape-props]]

     [:use {:xlinkHref (str "#" shape-id)
            :clipPath clip-path}]]))


; The SVG standard does not implement yet the 'stroke-alignment'
; attribute, to define the position of the stroke relative to the
; stroke axis (inner, center, outer). Here we implement a patch to be
; able to draw the stroke in the three cases. See discussion at:
; https://stackoverflow.com/questions/7241393/can-you-control-how-an-svgs-stroke-width-is-drawn
(mf/defc shape-custom-stroke
  {::mf/wrap-props false}
  [props]
  (let [child (obj/get props "children")
        shape (obj/get props "shape")
        stroke-width (:stroke-width shape 0)
        stroke-style (:stroke-style shape :none)
        stroke-position (:stroke-alignment shape :center)
        has-stroke? (and (> stroke-width 0)
                         (not= stroke-style :none))
        closed? (or (not= :path (:type shape))
                    (not (gsh/open-path? shape)))
        inner?  (= :inner stroke-position)
        outer?  (= :outer stroke-position)]

    (cond
      (and has-stroke? inner? closed?)
      [:& inner-stroke {:shape shape}
       child]

      (and has-stroke? outer? closed?)
      [:& outer-stroke {:shape shape}
       child]

      :else
      child)))

