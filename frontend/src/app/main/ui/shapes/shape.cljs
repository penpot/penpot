;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.shape
  (:require
   [app.common.geom.shapes :as geom]
   [app.common.uuid :as uuid]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc shape-container
  {::mf/wrap-props false}
  [props]
  (let [shape     (obj/get props "shape")
        children  (obj/get props "children")
        render-id (mf/use-memo #(str (uuid/next)))
        filter-id (str "filter_" render-id)
        styles    (cond-> (obj/new)
                    (:blocked shape) (obj/set! "pointerEvents" "none"))
        group-props (-> (obj/clone props)
                        (obj/without ["shape" "children"])
                        (obj/set! "id" (str "shape-" (:id shape)))
                        (obj/set! "className" (str "shape " (:type shape)))
                        (obj/set! "filter" (filters/filter-str filter-id shape))
                        (obj/set! "style" styles))]
    [:& (mf/provider muc/render-ctx) {:value render-id}
     [:> :g group-props
      [:defs
       [:& filters/filters {:shape shape :filter-id filter-id}]
       [:& grad/gradient   {:shape shape :attr :fill-color-gradient}]
       [:& grad/gradient   {:shape shape :attr :stroke-color-gradient}]]
      children]]))
