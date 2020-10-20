;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.path
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]
   [app.main.ui.shapes.group :refer [mask-id-ctx]]
   [app.common.geom.shapes :as geom]
   [app.util.object :as obj]))

;; --- Path Shape

(defn- render-path
  [{:keys [segments close?] :as shape}]
  (let [numsegs (count segments)]
    (loop [buffer []
           index 0]
      (cond
        (>= index numsegs)
        (if close?
          (str/join " " (conj buffer "Z"))
          (str/join " " buffer))

        (zero? index)
        (let [{:keys [x y] :as segment} (nth segments index)
              buffer (conj buffer (str/istr "M~{x},~{y}"))]
          (recur buffer (inc index)))

        :else
        (let [{:keys [x y] :as segment} (nth segments index)
              buffer (conj buffer (str/istr "L~{x},~{y}"))]
          (recur buffer (inc index)))))))

(mf/defc path-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        background? (unchecked-get props "background?")
        {:keys [id x y width height]} (geom/shape->rect-shape shape)
        mask-id (mf/use-ctx mask-id-ctx)
        transform (geom/transform-matrix shape)
        pdata (render-path shape)
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:transform transform
                        :id (str "shape-" id)
                        :d pdata}))]
    (if background?
      [:g {:mask mask-id}
       [:path {:stroke "transparent"
               :fill "transparent"
               :stroke-width "20px"
               :d pdata}]
       [:& shape-custom-stroke {:shape shape
                                :base-props props
                                :elem-name "path"}]]
      [:& shape-custom-stroke {:shape shape
                               :base-props props
                               :mask mask-id
                               :elem-name "path"}])))

