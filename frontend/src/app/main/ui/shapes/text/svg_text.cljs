;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.text.svg-text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.config :as cf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def fill-attrs [:fill-color :fill-color-gradient :fill-opacity])

(defn set-white-fill
  [shape]
  (let [update-color
        (fn [data]
          (-> data
              (dissoc :fill-color :fill-opacity :fill-color-gradient)
              (assoc :fills [{:fill-color "#FFFFFF" :fill-opacity 1}])))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(defn position-data-transform
  [shape {:keys [x y width height]}]
  (let [rect (gsh/make-rect x (- y height) width height)
        center (gsh/center-rect rect)]
    (when (or (:flip-x shape) (:flip-y shape))
      (-> (gmt/matrix)
          (gmt/translate center)

          (cond-> (:flip-x shape)
            (gmt/scale (gpt/point -1 1))

            (:flip-y shape)
            (gmt/scale (gpt/point 1 -1)))

          (gmt/translate (gpt/negate center))
          (dm/str)))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]

  (let [render-id (mf/use-ctx muc/render-id)
        shape (obj/get props "shape")
        shape (cond-> shape (:is-mask? shape) set-white-fill)

        {:keys [x y width height position-data]} shape

        transform (gsh/transform-str shape {:no-flip true})

        ;; These position attributes are not really necesary but they are convenient for for the export
        group-props (-> #js {:transform transform
                             :className "text-container"
                             :x x
                             :y y
                             :width width
                             :height height}
                        (attrs/add-style-attrs shape render-id))
        get-gradient-id
        (fn [index]
          (str render-id "_" (:id shape) "_" index))]

    [:*
     ;; Definition of gradients for partial elements
     (when (d/seek :fill-color-gradient position-data)
       [:defs
        (for [[index data] (d/enumerate position-data)]
          (when (some? (:fill-color-gradient data))
            (let [id (dm/str "fill-color-gradient_" (get-gradient-id index))]
              [:& grad/gradient {:id id
                                 :key id
                                 :attr :fill-color-gradient
                                 :shape data}])))])

     [:> :g group-props
      (for [[index data] (d/enumerate position-data)]
        (let [alignment-bl (when (cf/check-browser? :safari) "text-before-edge")
              dominant-bl  (when-not (cf/check-browser? :safari) "text-before-edge")
              rtl? (= "rtl" (:direction data))
              props (-> #js {:key (dm/str "text-" (:id shape) "-" index)
                             :x (if rtl? (+ (:x data) (:width data)) (:x data))
                             :y (- (:y data) (:height data))
                             :transform (position-data-transform shape data)
                             :alignmentBaseline alignment-bl
                             :dominantBaseline dominant-bl
                             :style (-> #js {:fontFamily (:font-family data)
                                             :fontSize (:font-size data)
                                             :fontWeight (:font-weight data)
                                             :textTransform (:text-transform data)
                                             :textDecoration (:text-decoration data)
                                             :letterSpacing (:letter-spacing data)
                                             :fontStyle (:font-style data)
                                             :direction (:direction data)
                                             :whiteSpace "pre"}
                                        (obj/set! "fill" (str "url(#fill-" index "-" render-id ")")))})
              shape (assoc shape :fills (:fills data))]

          [:& (mf/provider muc/render-id) {:key index :value (str render-id "_" (:id shape) "_" index)}
           [:& shape-custom-strokes {:shape shape :position index :render-id render-id}
            [:> :text props (:text data)]]]))]]))
