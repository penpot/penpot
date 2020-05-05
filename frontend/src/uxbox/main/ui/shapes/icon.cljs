;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.icon
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.util.object :as obj]))

(mf/defc icon-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height metadata rotation content]} shape

        transform (geom/transform-matrix shape)
        vbox      (apply str (interpose " " (:view-box metadata)))

        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:x x
                        :y y
                        :transform transform
                        :id (str "shape-" id)
                        :width width
                        :height height
                        :viewBox vbox
                        :preserveAspectRatio "none"
                        :dangerouslySetInnerHTML #js {:__html content}}))]
    [:g {:transform transform}
     [:> "svg" props]]))

(mf/defc icon-svg
  [{:keys [shape] :as props}]
  (let [{:keys [content id metadata]} shape
        view-box (apply str (interpose " " (:view-box metadata)))
        props {:viewBox view-box
               :id (str "shape-" id)
               :dangerouslySetInnerHTML #js {:__html content}}]
    [:& "svg" props]))
