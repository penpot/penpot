;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.bool
  (:require
   [app.common.geom.shapes :as gsh]
   [app.main.ui.hooks :refer [use-equal-memo]]
   [app.util.object :as obj]
   [app.util.path.bool :as pb]
   [app.util.path.shapes-to-path :as stp]
   [rumext.alpha :as mf]))

(defn bool-shape
  [shape-wrapper]
  (mf/fnc bool-shape
    {::mf/wrap-props false}
    [props]
    (let [frame  (obj/get props "frame")
          shape  (obj/get props "shape")
          childs (obj/get props "childs")]

      (when (> (count childs) 1)
        (let [shape-1 (stp/convert-to-path (nth childs 0))
              shape-2 (stp/convert-to-path (nth childs 1))

              content-1 (use-equal-memo (-> shape-1 gsh/transform-shape :content))
              content-2 (use-equal-memo (-> shape-2 gsh/transform-shape :content))

              content
              (mf/use-memo
               (mf/deps content-1 content-2)
               #(pb/content-bool (:bool-type shape) content-1 content-2))]

          [:*
           [:& shape-wrapper {:shape (-> shape
                                         (assoc :type :path)
                                         (assoc :content content))
                              :frame frame}]

           #_[:g
            (for [point (app.util.path.geom/content->points content)]
              [:circle {:cx (:x point)
                        :cy (:y point)
                        :r 1
                        :style {:fill "blue"}}])]])))))



