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

(mf/defc background-blur [{:keys [shape]}]
  (when-let [background-blur-filters (->> shape :blur (remove #(= (:type %) :layer-blur)) (remove :hidden))]
    (for [filter background-blur-filters]
      [:*
       

       [:foreignObject {:key (str "blur_" (:id filter))
                        :pointerEvents "none"
                        :x (:x shape)
                        :y (:y shape)
                        :width (:width shape)
                        :height (:height shape)
                        :transform (geom/transform-matrix shape)}
        [:style ""]
        [:div.backround-blur
         {:style {:width "100%"
                  :height "100%"
                  ;; :backdrop-filter (str/format "blur(%spx)" (:value filter))
                  :filter (str/format "blur(4px")
                  }}]]])))

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
                        (obj/set! "data-type" (:type shape))
                        (obj/set! "filter" (filters/filter-str filter-id shape)))

        ;;group-props (if (seq (:blur shape))
        ;;              (obj/set! group-props "clip-path" (str/fmt "url(#%s)" (str "blur_" render-id)))
        ;;              group-props)
        ]
    [:& (mf/provider muc/render-ctx) {:value render-id}
     [:> :g group-props
      [:defs
       [:& filters/filters {:shape shape :filter-id filter-id}]
       [:& grad/gradient   {:shape shape :attr :fill-color-gradient}]
       [:& grad/gradient   {:shape shape :attr :stroke-color-gradient}]

       #_(when (:blur shape)
         [:clipPath {:id (str "blur_" render-id)}
          children])]

      [:& background-blur {:shape shape}]
      children]]))
