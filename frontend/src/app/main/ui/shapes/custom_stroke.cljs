;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.custom-stroke
  (:require
   [app.common.data :as d]
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
        stroke-width (:stroke-width shape 0)]
    [:mask {:id stroke-mask-id}
     [:use {:xlinkHref (str "#" shape-id)
            :style #js {:fill "none" :stroke "white" :strokeWidth (* stroke-width 2)}}]

     [:use {:xlinkHref (str "#" shape-id)
            :style #js {:fill "black"}}]]))

(mf/defc stroke-defs
  [{:keys [shape render-id]}]
  (cond
    (and (= :inner (:stroke-alignment shape :center))
         (> (:stroke-width shape 0) 0))
    [:& inner-stroke-clip-path {:shape shape
                                :render-id render-id}]

    (and (= :outer (:stroke-alignment shape :center))
         (> (:stroke-width shape 0) 0))
    [:& outer-stroke-mask {:shape shape
                           :render-id render-id}]))

;; Outer alingmnent: display the shape in two layers. One
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
        inner? (= :inner stroke-position)
        outer? (= :outer stroke-position)]

    (cond
      (and has-stroke? inner?)
      [:& inner-stroke {:shape shape}
       child]

      (and has-stroke? outer?)
      [:& outer-stroke {:shape shape}
       child]

      :else
      child)))

