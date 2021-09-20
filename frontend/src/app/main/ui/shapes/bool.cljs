;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.path.bool :as pb]
   [app.common.path.shapes-to-path :as stp]
   [app.main.ui.hooks :refer [use-equal-memo]]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(defn bool-shape
  [shape-wrapper]
  (mf/fnc bool-shape
    {::mf/wrap-props false}
    [props]
    (let [frame  (obj/get props "frame")
          shape  (obj/get props "shape")
          childs (obj/get props "childs")

          childs (use-equal-memo childs)

          bool-content
          (mf/use-memo
           (mf/deps shape childs)
           (fn []
             (let [childs (d/mapm #(gsh/transform-shape %2) childs)]
               (->> (:shapes shape)
                    (map #(get childs %))
                    (map #(stp/convert-to-path % childs))
                    (mapv :content)
                    (pb/content-bool (:bool-type shape))))))]

      [:& shape-wrapper {:shape (-> shape
                                    (assoc :type :path)
                                    (assoc :content bool-content))
                         :frame frame}])))
