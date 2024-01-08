;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.circle
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc circle-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        x     (dm/get-prop shape :x)
        y     (dm/get-prop shape :y)
        w     (dm/get-prop shape :width)
        h     (dm/get-prop shape :height)

        t     (gsh/transform-str shape)

        cx    (+ x (/ w 2))
        cy    (+ y (/ h 2))
        rx    (/ w 2)
        ry    (/ h 2)

        props (mf/with-memo [shape]
                (-> #js {}
                    (obj/merge! #js {:cx cx :cy cy :rx rx :ry ry :transform t})))]

    [:& shape-custom-strokes {:shape shape}
     [:> :ellipse props]]))
