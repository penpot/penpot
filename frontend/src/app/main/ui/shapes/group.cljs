;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.group
  (:require
   [app.main.ui.shapes.mask :refer [mask-str clip-str mask-factory]]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(defn group-shape
  [shape-wrapper]
  (let [render-mask (mask-factory shape-wrapper)]
    (mf/fnc group-shape
      {::mf/wrap-props false}
      [props]
      (let [frame          (unchecked-get props "frame")
            shape          (unchecked-get props "shape")
            childs         (unchecked-get props "childs")

            masked-group?  (:masked-group? shape)

            [mask childs]  (if masked-group?
                             [(first childs) (rest childs)]
                             [nil childs])

            [mask-wrapper mask-props]
            (if masked-group?
              ["g" (-> (obj/new)
                       (obj/set! "clipPath" (clip-str mask))
                       (obj/set! "mask"     (mask-str mask)))]
              [mf/Fragment nil])]

        [:> mask-wrapper mask-props
         (when masked-group?
           [:> render-mask #js {:frame frame :mask mask}])

         (for [item childs]
           [:& shape-wrapper {:frame frame
                              :shape item
                              :key (:id item)}])]))))



