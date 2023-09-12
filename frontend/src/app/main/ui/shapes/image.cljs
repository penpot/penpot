;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.image
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]

  (let [shape     (unchecked-get props "shape")

        x         (dm/get-prop shape :x)
        y         (dm/get-prop shape :y)
        w         (dm/get-prop shape :width)
        h         (dm/get-prop shape :height)

        render-id (mf/use-ctx muc/render-id)
        transform (gsh/transform-str shape)

        props     (mf/with-memo [shape render-id]
                    (-> #js {}
                        (attrs/add-fill-props! shape render-id)
                        (attrs/add-border-props! shape)
                        (obj/merge! #js {:x x :y y :width w :height h :transform transform})))

        path?     (some? (.-d props))]

    [:& shape-custom-strokes {:shape shape}
     (if ^boolean path?
       [:> :path props]
       [:> :rect props])]))
