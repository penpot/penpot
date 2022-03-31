;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.attrs
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.spec.radius :as ctr]
   [app.common.spec.shape :refer [stroke-caps-line stroke-caps-marker]]
   [app.main.ui.context :as muc]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn- stroke-type->dasharray
  [width style]
  (let [values (case style
                 :mixed [5 5 1 5]
                 ;; We want 0 so they are circles
                 :dotted [(- width) 5]
                 :dashed [10 10]
                 nil)]

    (->> values (map #(+ % width)) (str/join ","))))


(defn add-border-radius [attrs {:keys [x y width height] :as shape}]
  (case (ctr/radius-mode shape)
    :radius-1
    (let [radius (gsh/shape-corners-1 shape)]
      (obj/merge! attrs #js {:rx radius :ry radius}))

    :radius-4
    (let [[r1 r2 r3 r4] (gsh/shape-corners-4 shape)
          top    (- width r1 r2)
          right  (- height r2 r3)
          bottom (- width r3 r4)
          left   (- height r4 r1)]
      (obj/merge! attrs #js {:d (dm/str
                                 "M" (+ x r1) "," y " "
                                 "h" top " "
                                 "a" r2 "," r2 " 0 0 1 " r2 "," r2 " "
                                 "v" right " "
                                 "a" r3 "," r3 " 0 0 1 " (- r3) "," r3 " "
                                 "h" (- bottom) " "
                                 "a" r4 "," r4 " 0 0 1 " (- r4) "," (- r4) " "
                                 "v" (- left) " "
                                 "a" r1 "," r1 " 0 0 1 " r1 "," (- r1) " "
                                 "z")}))
    attrs))

(defn add-fill
  ([attrs shape render-id]
   (add-fill attrs shape render-id nil))

  ([attrs shape render-id index]
   (let [fill-attrs
         (cond
           (contains? shape :fill-image)
           (let [fill-image-id (str "fill-image-" render-id)]
             {:fill (str "url(#" fill-image-id ")")})

           (contains? shape :fill-color-gradient)
           (let [fill-color-gradient-id (str "fill-color-gradient_" render-id (if index (str "_" index) ""))]
             {:fill (str "url(#" fill-color-gradient-id ")")})

           (contains? shape :fill-color)
           {:fill (:fill-color shape)}

           :else
           {:fill "none"})

         fill-attrs (cond-> fill-attrs
                      (contains? shape :fill-opacity)
                      (assoc :fillOpacity (:fill-opacity shape)))]

     (obj/merge! attrs (clj->js fill-attrs)))))

(defn add-stroke [attrs shape render-id index]
  (let [stroke-style (:stroke-style shape :none)
        stroke-color-gradient-id (str "stroke-color-gradient_" render-id "_" index)
        stroke-width (:stroke-width shape 1)]
    (if (not= stroke-style :none)
      (let [stroke-attrs
            (cond-> {:strokeWidth stroke-width}
              (:stroke-color-gradient shape)
              (assoc :stroke (str/format "url(#%s)" stroke-color-gradient-id))

              (and (not (:stroke-color-gradient shape))
                   (:stroke-color shape nil))
              (assoc :stroke (:stroke-color shape nil))

              (and (not (:stroke-color-gradient shape))
                   (:stroke-opacity shape nil))
              (assoc :strokeOpacity (:stroke-opacity shape nil))

              (not= stroke-style :svg)
              (assoc :strokeDasharray (stroke-type->dasharray stroke-width stroke-style))

              ;; For simple line caps we use svg stroke-line-cap attribute. This
              ;; only works if all caps are the same and we are not using the tricks
              ;; for inner or outer strokes.
              (and (stroke-caps-line (:stroke-cap-start shape))
                   (= (:stroke-cap-start shape) (:stroke-cap-end shape))
                   (not (#{:inner :outer} (:stroke-alignment shape)))
                   (not= :dotted stroke-style))
              (assoc :strokeLinecap (:stroke-cap-start shape))

              (= :dotted stroke-style)
              (assoc :strokeLinecap "round")

              ;; For other cap types we use markers.
              (and (or (stroke-caps-marker (:stroke-cap-start shape))
                       (and (stroke-caps-line (:stroke-cap-start shape))
                            (not= (:stroke-cap-start shape) (:stroke-cap-end shape))))
                   (not (#{:inner :outer} (:stroke-alignment shape))))
              (assoc :markerStart
                     (str/format "url(#marker-%s-%s)" render-id (name (:stroke-cap-start shape))))

              (and (or (stroke-caps-marker (:stroke-cap-end shape))
                       (and (stroke-caps-line (:stroke-cap-end shape))
                            (not= (:stroke-cap-start shape) (:stroke-cap-end shape))))
                   (not (#{:inner :outer} (:stroke-alignment shape))))
              (assoc :markerEnd
                     (str/format "url(#marker-%s-%s)" render-id (name (:stroke-cap-end shape)))))]

        (obj/merge! attrs (clj->js stroke-attrs)))
      attrs)))

(defn add-layer-props [attrs shape]
  (cond-> attrs
    (:opacity shape)
    (obj/set! "opacity" (:opacity shape))))

(defn extract-svg-attrs
  [render-id svg-defs svg-attrs]
  (let [replace-id (fn [id]
                     (if (contains? svg-defs id)
                       (str render-id "-" id)
                       id))
        svg-attrs (-> svg-attrs
                      (usvg/clean-attrs)
                      (usvg/update-attr-ids replace-id)
                      (dissoc :id))

        attrs  (-> svg-attrs (dissoc :style) (clj->js))
        styles (-> svg-attrs (:style {}) (clj->js))]
    [attrs styles]))

(defn add-style-attrs
  ([props shape]
   (let [render-id (mf/use-ctx muc/render-ctx)]
     (add-style-attrs props shape render-id)))

  ([props shape render-id]
   (let [svg-defs  (:svg-defs shape {})
         svg-attrs (:svg-attrs shape {})

         [svg-attrs svg-styles]
         (extract-svg-attrs render-id svg-defs svg-attrs)

         styles (-> (obj/get props "style" (obj/new))
                    (obj/merge! svg-styles)
                    (add-layer-props shape))

         styles (cond (or (some? (:fill-image shape))
                          (= :image (:type shape))
                          (> (count (:fills shape)) 1)
                          (some #(some? (:fill-color-gradient %)) (:fills shape)))
                      (obj/set! styles "fill" (str "url(#fill-0-" render-id ")"))

                      ;; imported svgs can have fill and fill-opacity attributes
                      (and (some? svg-styles) (obj/contains? svg-styles "fill"))
                      (-> styles
                          (obj/set! "fill" (obj/get svg-styles "fill"))
                          (obj/set! "fillOpacity" (obj/get svg-styles "fillOpacity")))

                      (and (some? svg-attrs) (obj/contains? svg-attrs "fill"))
                      (-> styles
                          (obj/set! "fill" (obj/get svg-attrs "fill"))
                          (obj/set! "fillOpacity" (obj/get svg-attrs "fillOpacity")))

                      ;; If contains svg-attrs the origin is svg. If it's not svg origin
                      ;; we setup the default fill as transparent (instead of black)
                      (and (contains? shape :svg-attrs)
                           (#{:svg-raw :group} (:type shape))
                           (empty? (:fills shape)))
                      styles

                      (d/not-empty? (:fills shape))
                      (add-fill styles (d/without-nils (get-in shape [:fills 0])) render-id 0)

                      :else
                      styles)]

     (-> props
         (obj/merge! svg-attrs)
         (add-border-radius shape)
         (obj/set! "style" styles)))))

(defn extract-style-attrs
  [shape]
  (-> (obj/new)
      (add-style-attrs shape)))

(defn extract-fill-attrs
  [shape render-id index]
  (let [fill-styles (-> (obj/get shape "style" (obj/new))
                        (add-fill shape render-id index))]
    (-> (obj/new)
        (obj/set! "style" fill-styles))))

(defn extract-stroke-attrs
  [shape index render-id]
  (let [stroke-styles (-> (obj/get shape "style" (obj/new))
                          (add-stroke shape render-id index))]
    (-> (obj/new)
        (obj/set! "style" stroke-styles))))

(defn extract-border-radius-attrs
  [shape]
  (-> (obj/new)
      (add-border-radius shape)))
