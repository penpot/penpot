;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.text
  (:require
   [app.common.text :as txt]
   [app.main.fonts :as fonts]
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.main.ui.shapes.text.svg-text :as svg]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- load-fonts!
  [content]
  (let [default (:font-id txt/default-text-attrs)]
    (->> (tree-seq map? :children content)
         (into #{default} (keep :font-id))
         (run! fonts/ensure-loaded!))))

(mf/defc text-shape
  {::mf/wrap-props false}
  [props]
  (let [{:keys [position-data content] :as shape} (obj/get props "shape")]

    (mf/with-memo [content]
      (load-fonts! content))

    (if (some? position-data)
      [:> svg/text-shape props]
      [:> fo/text-shape props])))
