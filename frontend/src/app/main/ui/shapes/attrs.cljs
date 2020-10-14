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
   [cuerdas.core :as str]
   [app.util.object :as obj]))

(defn- stroke-type->dasharray
  [style]
  (case style
    :mixed "5,5,1,5"
    :dotted "5,5"
    :dashed "10,10"
    nil))

(defn add-border-radius [attrs shape]
  (obj/merge! attrs #js {:rx (:rx shape)
                         :ry (:ry shape)}))

(defn add-fill [attrs shape]
  (let [fill-color-gradient-id (str "fill-color-gradient_" (:render-id shape))]
    (if (:fill-color-gradient shape)
      (obj/merge! attrs #js {:fill (str/format "url(#%s)" fill-color-gradient-id)})
      (obj/merge! attrs #js {:fill (or (:fill-color shape) "transparent")
                             :fillOpacity (:fill-opacity shape nil)}))))

(defn add-stroke [attrs shape]
  (let [stroke-style (:stroke-style shape :none)
        stroke-color-gradient-id (str "stroke-color-gradient_" (:render-id shape))]
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
   (-> (obj/new)
       (add-border-radius shape)
       (add-fill shape)
       (add-stroke shape))))
