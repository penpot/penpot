;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.attrs
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape :refer [stroke-caps-line stroke-caps-marker]]
   [app.common.types.shape.radius :as ctsr]
   [app.main.ui.context :as muc]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

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
  (case (ctsr/radius-mode shape)
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
  ([attrs fill-data render-id type]
   (add-fill attrs fill-data render-id nil type))

  ([attrs fill-data render-id index type]
   (let [fill-attrs
         (cond
           (contains? fill-data :fill-image)
           (let [fill-image-id (str "fill-image-" render-id)]
             {:fill (str "url(#" fill-image-id ")")})

           (and (contains? fill-data :fill-color-gradient) (some? (:fill-color-gradient fill-data)))
           (let [fill-color-gradient-id (str "fill-color-gradient_" render-id (if index (str "_" index) ""))]
             {:fill (str "url(#" fill-color-gradient-id ")")})

           (contains? fill-data :fill-color)
           {:fill (:fill-color fill-data)}

           :else
           {:fill "none"})

         fill-attrs (cond-> fill-attrs
                      (contains? fill-data :fill-opacity)
                      (assoc :fillOpacity (:fill-opacity fill-data))

                      ;; Old texts with only an opacity set are black by default
                      (and (= type :text) (nil? (:fill-color-gradient fill-data)) (nil? (:fill-color fill-data)))
                      (assoc :fill "black"))]

     (obj/merge! attrs (clj->js fill-attrs)))))

(defn add-stroke [attrs stroke-data render-id index]
  (let [stroke-style (:stroke-style stroke-data :none)
        stroke-color-gradient-id (str "stroke-color-gradient_" render-id "_" index)
        stroke-width (:stroke-width stroke-data 1)]
    (if (not= stroke-style :none)
      (let [stroke-attrs
            (cond-> {:strokeWidth stroke-width}
              (:stroke-color-gradient stroke-data)
              (assoc :stroke (str/format "url(#%s)" stroke-color-gradient-id))

              (and (not (:stroke-color-gradient stroke-data))
                   (:stroke-color stroke-data nil))
              (assoc :stroke (:stroke-color stroke-data nil))

              (and (not (:stroke-color-gradient stroke-data))
                   (:stroke-opacity stroke-data nil))
              (assoc :strokeOpacity (:stroke-opacity stroke-data nil))

              (not= stroke-style :svg)
              (assoc :strokeDasharray (stroke-type->dasharray stroke-width stroke-style))

              ;; For simple line caps we use svg stroke-line-cap attribute. This
              ;; only works if all caps are the same and we are not using the tricks
              ;; for inner or outer strokes.
              (and (stroke-caps-line (:stroke-cap-start stroke-data))
                   (= (:stroke-cap-start stroke-data) (:stroke-cap-end stroke-data))
                   (not (#{:inner :outer} (:stroke-alignment stroke-data)))
                   (not= :dotted stroke-style))
              (assoc :strokeLinecap (:stroke-cap-start stroke-data))

              (= :dotted stroke-style)
              (assoc :strokeLinecap "round")

              ;; For other cap types we use markers.
              (and (or (stroke-caps-marker (:stroke-cap-start stroke-data))
                       (and (stroke-caps-line (:stroke-cap-start stroke-data))
                            (not= (:stroke-cap-start stroke-data) (:stroke-cap-end stroke-data))))
                   (not (#{:inner :outer} (:stroke-alignment stroke-data))))
              (assoc :markerStart
                     (str/format "url(#marker-%s-%s)" render-id (name (:stroke-cap-start stroke-data))))

              (and (or (stroke-caps-marker (:stroke-cap-end stroke-data))
                       (and (stroke-caps-line (:stroke-cap-end stroke-data))
                            (not= (:stroke-cap-start stroke-data) (:stroke-cap-end stroke-data))))
                   (not (#{:inner :outer} (:stroke-alignment stroke-data))))
              (assoc :markerEnd
                     (str/format "url(#marker-%s-%s)" render-id (name (:stroke-cap-end stroke-data)))))]

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
   (let [render-id (mf/use-ctx muc/render-id)]
     (add-style-attrs props shape render-id)))

  ([props shape render-id]
   (let [svg-defs  (:svg-defs shape {})
         svg-attrs (:svg-attrs shape {})

         [svg-attrs svg-styles]
         (extract-svg-attrs render-id svg-defs svg-attrs)

         styles (-> (obj/get props "style" (obj/create))
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
                      ;; we setup the default fill as black
                      (and (contains? shape :svg-attrs)
                           (#{:svg-raw :group} (:type shape))
                           (empty? (:fills shape)))
                      (-> styles
                          (obj/set! "fill" (or (obj/get (:wrapper-styles shape) "fill") clr/black)))

                      (d/not-empty? (:fills shape))
                      (add-fill styles (d/without-nils (get-in shape [:fills 0])) render-id 0 (:type shape))

                      :else
                      styles)]

     (-> props
         (obj/merge! svg-attrs)
         (add-border-radius shape)
         (obj/set! "style" styles)))))

(defn extract-style-attrs
  [shape]
  (-> (obj/create)
      (add-style-attrs shape)))

(defn extract-fill-attrs
  [fill-data render-id index type]
  (let [fill-styles (-> (obj/get fill-data "style" (obj/create))
                        (add-fill fill-data render-id index type))]
    (-> (obj/create)
        (obj/set! "style" fill-styles))))

(defn extract-stroke-attrs
  [stroke-data index render-id]
  (let [stroke-styles (-> (obj/get stroke-data "style" (obj/create))
                          (add-stroke stroke-data render-id index))]
    (-> (obj/create)
        (obj/set! "style" stroke-styles))))

(defn extract-border-radius-attrs
  [shape]
  (-> (obj/create)
      (add-border-radius shape)))
