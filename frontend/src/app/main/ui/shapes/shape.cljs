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
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.object :as obj]
   [app.common.uuid :as uuid]
   [app.common.geom.shapes :as geom]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.gradients :as grad]
   [app.main.ui.context :as muc]))

(mf/defc shape-container
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        children (unchecked-get props "children")
        render-id (mf/use-memo #(str (uuid/next)))
        filter-id (str "filter_" render-id)
        group-props (-> props
                        (obj/clone)
                        (obj/without ["shape" "children"])
                        (obj/set! "className" "shape")
                        (obj/set! "filter" (filters/filter-str filter-id shape)))]
    [:& (mf/provider muc/render-ctx) {:value render-id}
     [:> :g group-props
      [:defs
       [:& filters/filters {:shape shape :filter-id filter-id}]
       [:& grad/gradient   {:shape shape :attr :fill-color-gradient}]
       [:& grad/gradient   {:shape shape :attr :stroke-color-gradient}]]
      
      children]]))
