;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.path
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]
   [app.util.object :as obj]
   [app.util.path.format :as upf]))

;; --- Path Shape

(mf/defc path-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        background? (unchecked-get props "background?")
        {:keys [id x y width height]} (:selrect shape)
        content (:content shape)
        pdata (mf/use-memo (mf/deps content) #(upf/format-path content))
        props (-> (attrs/extract-style-attrs shape)
                  (obj/merge!
                   #js {:d pdata}))]
    (if background?
      [:g
       [:path {:stroke "none"
               :fill "none"
               :stroke-width "20px"
               :d pdata}]
       [:& shape-custom-stroke {:shape shape
                                :base-props props
                                :elem-name "path"}]]
      [:& shape-custom-stroke {:shape shape
                               :base-props props
                               :elem-name "path"}])))

