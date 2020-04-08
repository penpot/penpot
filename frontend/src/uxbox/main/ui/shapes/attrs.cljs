;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.attrs
  (:require
   [cuerdas.core :as str]
   [uxbox.util.interop :as itr]))

(defn- stroke-type->dasharray
  [style]
  (case style
    :mixed "5,5,1,5"
    :dotted "5,5"
    :dashed "10,10"
    nil))

(defn extract-style-attrs
  [shape]
  (let [stroke-style (:stroke-style shape :none)
        attrs #js {:fill (:fill-color shape nil)
                   :fillOpacity (:fill-opacity shape nil)
                   :rx (:rx shape nil)
                   :ry (:ry shape nil)}]
    (when (not= stroke-style :none)
      (itr/obj-assign! attrs
                       #js {:stroke (:stroke-color shape nil)
                            :strokeWidth (:stroke-width shape nil)
                            :strokeOpacity (:stroke-opacity shape nil)
                            :strokeDasharray (stroke-type->dasharray stroke-style)}))
    attrs))
