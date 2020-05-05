;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.group
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.util.debug :refer [debug?]]
   [uxbox.util.geom.shapes :as geom]))

(defn group-shape
  [shape-wrapper]
  (mf/fnc group-shape
    {::mf/wrap-props false}
    [props]
    (let [frame (unchecked-get props "frame")
          shape (unchecked-get props "shape")
          children (unchecked-get props "children")
          is-child-selected? (unchecked-get props "is-child-selected?")
          {:keys [id x y width height]} shape
          transform (geom/transform-matrix shape)]
      [:g
       (for [item children]
         [:& shape-wrapper
          {:frame frame :shape item :key (:id item)}])

       (when (not is-child-selected?)
         [:rect {:transform transform
                 :x x
                 :y y
                 :fill (if (debug? :group) "red" "transparent")
                 :opacity 0.5
                 :id (str "group-" id)
                 :width width
                 :height height}])])))


