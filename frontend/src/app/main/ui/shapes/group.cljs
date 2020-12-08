;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.group
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.main.ui.shapes.attrs :as attrs]
   [app.common.geom.shapes :as geom]))

(defn group-shape
  [shape-wrapper]
  (mf/fnc group-shape
    {::mf/wrap-props false}
    [props]
    (let [frame          (unchecked-get props "frame")
          shape          (unchecked-get props "shape")
          childs         (unchecked-get props "childs")
          expand-mask    (unchecked-get props "expand-mask")
          pointer-events (unchecked-get props "pointer-events")
          mask        (if (and (:masked-group? shape) (not expand-mask))
                        (first childs)
                        nil)
          childs      (if (and (:masked-group? shape) (not expand-mask))
                        (rest childs)
                        childs)
          {:keys [id x y width height]} shape
          transform (geom/transform-matrix shape)]
      [:g.group {:pointer-events pointer-events
                 :mask (when (and mask (not expand-mask))
                         (str/fmt "url(#%s)" (:id mask)))}
       (when mask
         [:defs
          [:mask {:id (:id mask)
                  :width width
                  :height height}
           [:& shape-wrapper {:frame frame
                              :shape mask}]]])
       (for [item childs]
         [:& shape-wrapper {:frame frame
                            :shape item
                            :key (:id item)}])])))



