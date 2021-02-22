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
   [app.util.object :as obj]
   [rumext.alpha :as mf]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.mask :refer [mask-str mask-factory]]))

(defn group-shape
  [shape-wrapper]
  (let [render-mask (mask-factory shape-wrapper)]
    (mf/fnc group-shape
      {::mf/wrap-props false}
      [props]
      (let [frame          (unchecked-get props "frame")
            shape          (unchecked-get props "shape")
            childs         (unchecked-get props "childs")
            expand-mask    (unchecked-get props "expand-mask")
            pointer-events (unchecked-get props "pointer-events")

            {:keys [id x y width height]} shape

            show-mask?     (and (:masked-group? shape) (not expand-mask))
            mask           (when show-mask? (first childs))
            childs         (if show-mask? (rest childs) childs)

            props (-> (attrs/extract-style-attrs shape)
                      (obj/merge!
                       #js {:className "group"
                            :pointerEvents pointer-events
                            :mask (when (and mask (not expand-mask)) (mask-str mask))}))]

        [:> :g props
         (when mask
           [:> render-mask #js {:frame frame :mask mask}])

         (for [item childs]
           [:& shape-wrapper {:frame frame
                              :shape item
                              :key (:id item)}])]))))



