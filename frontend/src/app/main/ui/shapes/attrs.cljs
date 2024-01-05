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
   [app.common.svg :as csvg]
   [app.common.types.shape :refer [stroke-caps-line stroke-caps-marker]]
   [app.common.types.shape.radius :as ctsr]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn- stroke-type->dasharray
  [width style]
  (let [values (case style
                 :mixed [5 5 1 5]
                 ;; We want 0 so they are circles
                 :dotted [(- width) 5]
                 :dashed [10 10]
                 nil)]

    (->> values (map #(+ % width)) (str/join ","))))

(defn get-border-radius
  [shape]
  (case (ctsr/radius-mode shape)
    :radius-1
    (let [radius (gsh/shape-corners-1 shape)]
      #js {:rx radius :ry radius})

    :radius-4
    (let [[r1 r2 r3 r4] (gsh/shape-corners-4 shape)
          x      (dm/get-prop shape :x)
          y      (dm/get-prop shape :y)
          width  (dm/get-prop shape :width)
          height (dm/get-prop shape :height)
          top    (- width r1 r2)
          right  (- height r2 r3)
          bottom (- width r3 r4)
          left   (- height r4 r1)]
      #js {:d (dm/str
               "M" (+ x r1) "," y " "
               "h" top " "
               "a" r2 "," r2 " 0 0 1 " r2 "," r2 " "
               "v" right " "
               "a" r3 "," r3 " 0 0 1 " (- r3) "," r3 " "
               "h" (- bottom) " "
               "a" r4 "," r4 " 0 0 1 " (- r4) "," (- r4) " "
               "v" (- left) " "
               "a" r1 "," r1 " 0 0 1 " r1 "," (- r1) " "
               "z")})))

(defn add-border-props!
  [props shape]
  (obj/merge! props (get-border-radius shape)))

(defn add-fill!
  [attrs fill-data render-id index type]
  (let [index (if (some? index) (dm/str "-" index) "")]
    (cond
      (contains? fill-data :fill-image)
      (let [id (dm/str "fill-image-" render-id)]
        (obj/set! attrs "fill" (dm/str "url(#" id ")")))

      (some? (:fill-color-gradient fill-data))
      (let [id (dm/str "fill-color-gradient-" render-id index)]
        (obj/set! attrs "fill" (dm/str "url(#" id ")")))

      (contains? fill-data :fill-color)
      (obj/set! attrs "fill" (:fill-color fill-data))

      :else
      (obj/set! attrs "fill" "none"))

    (when (contains? fill-data :fill-opacity)
      (obj/set! attrs "fillOpacity" (:fill-opacity fill-data)))

    (when (and (= :text type)
               (nil? (:fill-color-gradient fill-data))
               (nil? (:fill-color fill-data)))
      (obj/set! attrs "fill" "black"))

    attrs))

(defn add-stroke!
  [attrs data render-id index]
  (let [style (:stroke-style data :solid)]
    (when-not (= style :none)
      (let [width       (:stroke-width data 1)
            gradient    (:stroke-color-gradient data)
            color       (:stroke-color data)
            opacity     (:stroke-opacity data)]

        (obj/set! attrs "strokeWidth" width)

        (when (some? gradient)
          (let [gradient-id (dm/str "stroke-color-gradient-" render-id "-" index)]
            (obj/set! attrs "stroke" (str/ffmt "url(#%)" gradient-id))))

        (when-not (some? gradient)
          (when (some? color)
            (obj/set! attrs "stroke" color))
          (when (some? opacity)
            (obj/set! attrs "strokeOpacity" opacity)))

        (when (not= style :svg)
          (obj/set! attrs "strokeDasharray" (stroke-type->dasharray width style)))

        ;; For simple line caps we use svg stroke-line-cap attribute. This
        ;; only works if all caps are the same and we are not using the tricks
        ;; for inner or outer strokes.
        (let [caps-start (:stroke-cap-start data)
              caps-end   (:stroke-cap-end data)
              alignment  (:stroke-alignment data)]
          (cond
            (and (contains? stroke-caps-line caps-start)
                 (= caps-start caps-end)
                 (not= :inner alignment)
                 (not= :outer alignment)
                 (not= :dotted style))
            (obj/set! attrs "strokeLinecap" (name caps-start))

            (= :dotted style)
            (obj/set! attrs "strokeLinecap" "round"))

          (when (and (not= :inner alignment)
                     (not= :outer alignment))

            ;; For other cap types we use markers.
            (when (or (contains? stroke-caps-marker caps-start)
                      (and (contains? stroke-caps-line caps-start)
                           (not= caps-start caps-end)))
              (obj/set! attrs "markerStart" (str/ffmt "url(#marker-%-%)" render-id (name caps-start))))

            (when (or (contains? stroke-caps-marker caps-end)
                      (and (contains? stroke-caps-line caps-end)
                           (not= caps-start caps-end)))
              (obj/set! attrs "markerEnd" (str/ffmt "url(#marker-%-%)" render-id (name caps-end))))))))

        attrs))

(defn add-layer-props!
  [props shape]
  (let [opacity (:opacity shape)]
    (if (some? opacity)
      (obj/set! props "opacity" opacity)
      props)))

(defn get-svg-props
  [shape render-id]
  (let [attrs (get shape :svg-attrs {})
        defs  (get shape :svg-defs {})]
    (if (and (empty? defs)
             (empty? attrs))
      #js {}
      (-> attrs
          (csvg/update-attr-ids
           (fn [id]
             (if (contains? defs id)
               (dm/str render-id "-" id)
               id)))
          (dissoc :id)
          (obj/map->obj)))))

(defn add-fill-props!
  [props shape render-id]
  (let [svg-attrs   (get-svg-props shape render-id)
        svg-style   (obj/get svg-attrs "style")

        shape-type  (dm/get-prop shape :type)

        shape-fills (get shape :fills [])
        fill-image  (get shape :fill-image)

        style       (-> (obj/get props "style" #js {})
                        (obj/merge! svg-style)
                        (add-layer-props! shape))]

    (cond
      (or (some? fill-image)
          (= :image shape-type)
          (> (count shape-fills) 1)
          (some #(some? (:fill-color-gradient %)) shape-fills))
      (obj/set! style "fill" (dm/str "url(#fill-0-" render-id ")"))

      ;; imported svgs can have fill and fill-opacity attributes
      (contains? svg-style "fill")
      (-> style
          (obj/set! "fill" (obj/get svg-style "fill"))
          (obj/set! "fillOpacity" (obj/get svg-style "fillOpacity")))

      (obj/contains? svg-attrs "fill")
      (-> style
          (obj/set! "fill" (obj/get svg-attrs "fill"))
          (obj/set! "fillOpacity" (obj/get svg-attrs "fillOpacity")))

      ;; If the shape comes from an imported SVG (we know because
      ;; it has the :svg-attrs atribute), and it does not have an
      ;; own fill, we set a default black fill. This will be
      ;; inherited by child nodes, and is for emulating the
      ;; behavior of standard SVG, in that a node that has no
      ;; explicit fill has a default fill of black. This may be
      ;; reset to normal if a Penpot frame shape appears below
      ;; (see main.ui.shapes.frame/frame-container).
      (and (contains? shape :svg-attrs)
           (or (= :svg-raw shape-type)
               (= :group shape-type))
           (empty? shape-fills))
      (let [wstyle (get shape :wrapper-styles)
            fill   (obj/get wstyle "fill")
            fill   (d/nilv fill clr/black)]
        (obj/set! style "fill" fill))

      (d/not-empty? shape-fills)
      (let [fill (d/without-nils (nth shape-fills 0))]
        (add-fill! style fill render-id 0 shape-type)))

    (-> props
        (obj/merge! svg-attrs)
        (obj/set! "style" style))))

(defn get-style-props
  [shape render-id]
  (-> #js {}
      (add-fill-props! shape render-id)
      (add-border-props! shape)))

(defn get-stroke-style
  [stroke-data index render-id]
  (add-stroke! #js {} stroke-data render-id index))

(defn get-fill-style
  [fill-data index render-id type]
  (add-fill! #js {} fill-data render-id index type))

(defn extract-border-radius-attrs
  [shape]
  (-> (obj/create)
      (add-border-props! shape)))

(defn get-border-radius-props
  [shape]
  (add-border-props! #js {} shape))
