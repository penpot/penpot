;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.shape
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.gradients :as grad]
   [app.main.ui.shapes.svg-defs :as defs]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc shape-container
  {::mf/wrap-props false}
  [props]
  (let [shape          (obj/get props "shape")
        children       (obj/get props "children")
        pointer-events (obj/get props "pointer-events")
        render-id      (mf/use-memo #(str (uuid/next)))
        filter-id      (str "filter_" render-id)
        styles         (-> (obj/new)
                           (obj/set! "pointerEvents" pointer-events)

                           (cond-> (and (:blend-mode shape) (not= (:blend-mode shape) :normal))
                             (obj/set! "mixBlendMode" (d/name (:blend-mode shape)))))

        {:keys [x y width height type]} shape
        frame? (= :frame type)
        group-props (-> (obj/clone props)
                        (obj/without ["shape" "children"])
                        (obj/set! "id" (str "shape-" (:id shape)))
                        (obj/set! "filter" (filters/filter-str filter-id shape))
                        (obj/set! "style" styles)

                        (cond-> frame?
                          (-> (obj/set! "x" x)
                              (obj/set! "y" y)
                              (obj/set! "width" width)
                              (obj/set! "height" height)
                              (obj/set! "xmlnsXlink" "http://www.w3.org/1999/xlink")
                              (obj/set! "xmlns" "http://www.w3.org/2000/svg"))))

        wrapper-tag (if frame? "svg" "g")]
    [:& (mf/provider muc/render-ctx) {:value render-id}
     [:> wrapper-tag group-props
      [:defs
       [:& defs/svg-defs   {:shape shape :render-id render-id}]
       [:& filters/filters {:shape shape :filter-id filter-id}]
       [:& grad/gradient   {:shape shape :attr :fill-color-gradient}]
       [:& grad/gradient   {:shape shape :attr :stroke-color-gradient}]]
      children]]))
