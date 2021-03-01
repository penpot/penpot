;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 UXBOX Labs SL

(ns app.main.ui.shapes.attrs
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.object :as obj]
   [app.main.ui.context :as muc]))

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
  (let [fill-color-gradient-id (str "fill-color-gradient_" render-id)]
    (cond
      (:fill-color-gradient shape)
      (obj/merge! attrs #js {:fill (str/format "url(#%s)" fill-color-gradient-id)})

      (and (not= :svg-raw (:type shape))
           (not (:fill-color-gradient shape)))
      (obj/merge! attrs #js {:fill (or (:fill-color shape) "transparent")
                             :fillOpacity (:fill-opacity shape nil)})

      (and (= :svg-raw (:type shape))
           (or (:fill-opacity shape) (:fill-color shape)))
      (obj/merge! attrs #js {:fill (:fill-color shape)
                             :fillOpacity (:fill-opacity shape nil)})

      :else attrs)))

(defn add-stroke [attrs shape render-id]
  (let [stroke-style (:stroke-style shape :none)
        stroke-color-gradient-id (str "stroke-color-gradient_" render-id)]
    (if (not= stroke-style :none)
      (if (:stroke-color-gradient shape)
        (obj/merge! attrs
                    #js {:stroke (str/format "url(#%s)" stroke-color-gradient-id)
                         :strokeWidth (:stroke-width shape 1)
                         :strokeDasharray (stroke-type->dasharray stroke-style)})
        (obj/merge! attrs
                    #js {:stroke (:stroke-color shape nil)
                         :strokeWidth (:stroke-width shape 1)
                         :strokeOpacity (:stroke-opacity shape nil)
                         :strokeDasharray (stroke-type->dasharray stroke-style)}))))
  attrs)

(defn extract-style-attrs
  ([shape]
   (let [render-id (mf/use-ctx muc/render-ctx)
         styles (-> (obj/new)
                    (add-fill shape render-id)
                    (add-stroke shape render-id))]
     (-> (obj/new)
         (add-border-radius shape)
         (obj/set! "style" styles)))))
