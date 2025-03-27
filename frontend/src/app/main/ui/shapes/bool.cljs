;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.bool
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.path :as path]

   [app.main.ui.hooks :as h]
   [app.main.ui.shapes.export :as use]
   [app.main.ui.shapes.path :refer [path-shape]]
   [rumext.v2 :as mf]))

(defn bool-shape
  [shape-wrapper]
  (mf/fnc bool-shape
    {::mf/wrap-props false}
    [props]
    (let [shape       (unchecked-get props "shape")
          child-objs  (unchecked-get props "childs")
          child-objs  (h/use-equal-memo child-objs)

          metadata? (mf/use-ctx use/include-metadata-ctx)
          content   (mf/with-memo [shape child-objs]
                      (let [content (:content shape)]
                        (cond
                          (some? content)
                          content

                          (some? child-objs)
                          (path/calc-bool-content shape child-objs))))

          shape     (mf/with-memo [shape content]
                      (assoc shape :content content))]

      [:*
       (when (some? content)
         [:& path-shape {:shape shape}])

       (when metadata?
         [:> "penpot:bool" {}
          (for [item (map #(get child-objs %) (:shapes shape))]
            [:& shape-wrapper
             {:shape item
              :key (dm/str (dm/get-prop item :id))}])])])))
