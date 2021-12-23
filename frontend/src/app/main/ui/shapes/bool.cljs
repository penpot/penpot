;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.bool
  (:require
   [app.common.geom.shapes :as gsh]
   [app.main.ui.hooks :refer [use-equal-memo]]
   [app.main.ui.shapes.export :as use]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(defn bool-shape
  [shape-wrapper]
  (mf/fnc bool-shape
          {::mf/wrap-props false}
          [props]
          (let [shape  (obj/get props "shape")
                childs (obj/get props "childs")
                childs (use-equal-memo childs)
                include-metadata? (mf/use-ctx use/include-metadata-ctx)

                bool-content
                (mf/use-memo
                 (mf/deps shape childs)
                 #(or (:bool-content shape)
                      (gsh/calc-bool-content shape childs)))]

            [:*
             [:& path-shape {:shape (assoc shape :content bool-content)}]

             (when include-metadata?
               [:> "penpot:bool" {}
                (for [item (->> (:shapes shape) (mapv #(get childs %)))]
                  [:& shape-wrapper {:shape item
                                     :key (:id item)}])])])))
