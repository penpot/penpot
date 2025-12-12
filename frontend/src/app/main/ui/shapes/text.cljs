;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.text
  (:require
   [app.common.types.text :as txt]
   [app.main.fonts :as fonts]
   [app.main.ui.context :as ctx]
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.main.ui.shapes.text.svg-text :as svg]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- load-fonts!
  [content]
  (let [extract-fn (juxt :font-id :font-variant-id)
        default    (extract-fn txt/default-typography)]
    (->> (tree-seq map? :children content)
         (into #{default} (keep extract-fn))
         (run! (fn [[font-id variant-id]]
                 (when (some? font-id)
                   (fonts/ensure-loaded! font-id variant-id)))))))

(mf/defc text-shape
  {::mf/wrap-props false}
  [props]
  (let [{:keys [position-data content] :as shape} (obj/get props "shape")
        is-component? (mf/use-ctx ctx/is-component?)]

    (mf/with-memo [content]
      (load-fonts! content))

    ;; Old components can have texts without position data that must be rendered via foreign key
    (cond
      (some? position-data)
      [:> svg/text-shape props]

      ;; Only use this for component preview, otherwise the dashboard thumbnails
      ;; will give a tainted canvas error because the `foreignObject` cannot be
      ;; rendered.
      (and (nil? position-data) is-component?)
      [:> fo/text-shape props])))
