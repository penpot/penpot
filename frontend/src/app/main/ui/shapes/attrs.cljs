;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.attrs
  (:require
   [app.main.ui.context :as muc]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn- stroke-type->dasharray
  [style]
  (case style
    :mixed "5,5,1,5"
    :dotted "5,5"
    :dashed "10,10"
    nil))

(defn- truncate-side
  [shape ra-attr rb-attr dimension-attr]
  (let [ra        (ra-attr shape)
        rb        (rb-attr shape)
        dimension (dimension-attr shape)]
    (if (<= (+ ra rb) dimension)
      [ra rb]
      [(/ (* ra dimension) (+ ra rb))
       (/ (* rb dimension) (+ ra rb))])))

(defn- truncate-radius
  [shape]
  (let [[r-top-left r-top-right]
        (truncate-side shape :r1 :r2 :width)

        [r-right-top r-right-bottom]
        (truncate-side shape :r2 :r3 :height)

        [r-bottom-right r-bottom-left]
        (truncate-side shape :r3 :r4 :width)

        [r-left-bottom r-left-top]
        (truncate-side shape :r4 :r1 :height)]

    [(min r-top-left r-left-top)
     (min r-top-right r-right-top)
     (min r-right-bottom r-bottom-right)
     (min r-bottom-left r-left-bottom)]))

(defn add-border-radius [attrs shape]
  (if (or (:r1 shape) (:r2 shape) (:r3 shape) (:r4 shape))
    (let [[r1 r2 r3 r4] (truncate-radius shape)
          top    (- (:width shape) r1 r2)
          right  (- (:height shape) r2 r3)
          bottom (- (:width shape) r3 r4)
          left   (- (:height shape) r4 r1)]
      (obj/merge! attrs #js {:d (str "M" (+ (:x shape) r1) "," (:y shape) " "
                                     "h" top " "
                                     "a" r2 "," r2 " 0 0 1 " r2 "," r2 " "
                                     "v" right " "
                                     "a" r3 "," r3 " 0 0 1 " (- r3) "," r3 " "
                                     "h" (- bottom) " "
                                     "a" r4 "," r4 " 0 0 1 " (- r4) "," (- r4) " "
                                     "v" (- left) " "
                                     "a" r1 "," r1 " 0 0 1 " r1 "," (- r1) " "
                                     "z")}))
    (if (or (:rx shape) (:ry shape))
      (obj/merge! attrs #js {:rx (:rx shape)
                             :ry (:ry shape)})
      attrs)))

(defn add-fill [attrs shape render-id]
  (let [fill-attrs (cond
                     (contains? shape :fill-color-gradient)
                     (let [fill-color-gradient-id (str "fill-color-gradient_" render-id)]
                       {:fill (str/format "url(#%s)" fill-color-gradient-id)})

                     (contains? shape :fill-color)
                     {:fill (:fill-color shape)}

                     (contains? shape :fill-image)
                     (let [fill-image-id (str "fill-image-" render-id)]
                       {:fill (str/format "url(#%s)" fill-image-id) })

                     ;; If contains svg-attrs the origin is svg. If it's not svg origin
                     ;; we setup the default fill as transparent (instead of black)
                     (and (not (contains? shape :svg-attrs))
                          (not (#{ :svg-raw :group } (:type shape))))
                     {:fill "none"}

                     :else
                     {})

        fill-attrs (cond-> fill-attrs
                     (contains? shape :fill-opacity)
                     (assoc :fillOpacity (:fill-opacity shape)))]

    (obj/merge! attrs (clj->js fill-attrs))))

(defn add-stroke [attrs shape render-id]
  (let [stroke-style (:stroke-style shape :none)
        stroke-color-gradient-id (str "stroke-color-gradient_" render-id)]
    (if (not= stroke-style :none)
      (let [stroke-attrs
            (cond-> {:strokeWidth (:stroke-width shape 1)}
              (:stroke-color-gradient shape)
              (assoc :stroke (str/format "url(#%s)" stroke-color-gradient-id))

              (and (not (:stroke-color-gradient shape))
                   (:stroke-color shape nil))
              (assoc :stroke (:stroke-color shape nil))

              (and (not (:stroke-color-gradient shape))
                   (:stroke-opacity shape nil))
              (assoc :strokeOpacity (:stroke-opacity shape nil))

              (not= stroke-style :svg)
              (assoc :strokeDasharray (stroke-type->dasharray stroke-style)))]
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
                      (usvg/update-attr-ids replace-id))

        attrs  (-> svg-attrs (dissoc :style) (clj->js))
        styles (-> svg-attrs (:style {}) (clj->js))]
    [attrs styles]))

(defn add-style-attrs
  [props shape]
  (let [render-id (mf/use-ctx muc/render-ctx)
        svg-defs  (:svg-defs shape {})
        svg-attrs (:svg-attrs shape {})

        [svg-attrs svg-styles] (mf/use-memo
                                (mf/deps render-id svg-defs svg-attrs)
                                #(extract-svg-attrs render-id svg-defs svg-attrs))

        styles (-> (obj/get props "style" (obj/new))
                   (obj/merge! svg-styles)
                   (add-fill shape render-id)
                   (add-stroke shape render-id)
                   (add-layer-props shape))]

    (-> props
        (obj/merge! svg-attrs)
        (add-border-radius shape)
        (obj/set! "style" styles))))

(defn extract-style-attrs
  [shape]
  (-> (obj/new)
      (add-style-attrs shape)))
