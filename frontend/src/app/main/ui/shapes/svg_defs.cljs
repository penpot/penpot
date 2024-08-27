;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.svg-defs
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.json :as json]
   [app.common.svg :as csvg]
   [rumext.v2 :as mf]))

(defn add-matrix [attrs transform-key transform-matrix]
  (update attrs
          transform-key
          (fn [val]
            (if val
              (str transform-matrix " " val)
              (str transform-matrix)))))

(mf/defc svg-node
  {::mf/wrap-props false}
  [{:keys [type node prefix-id transform bounds]}]
  (cond
    (string? node) node

    :else
    (let [{:keys [tag attrs content]} node

          transform-gradient? (and (contains? csvg/gradient-tags tag)
                                   (= "userSpaceOnUse" (get attrs :gradientUnits "objectBoundingBox")))

          transform-pattern?  (and (= :pattern tag)
                                   (= "userSpaceOnUse" (get attrs :patternContentUnits "userSpaceOnUse"))
                                   (= "userSpaceOnUse" (get attrs :patternUnits "userSpaceOnUse")))

          transform-clippath? (and (= :clipPath tag)
                                   (= "userSpaceOnUse" (get attrs :clipPathUnits "userSpaceOnUse")))

          transform-filter?   (and (contains? csvg/filter-tags tag)
                                   (= "userSpaceOnUse" (get attrs :filterUnits "objectBoundingBox")))

          transform-mask?     (and (= :mask tag)
                                   (= "userSpaceOnUse" (get attrs :maskUnits "objectBoundingBox")))

          attrs
          (-> attrs
              (csvg/update-attr-ids prefix-id)
              (csvg/attrs->props)
              ;; This clasname will be used to change the transform on the viewport
              ;; only necessary for groups because shapes have their own transform
              (cond-> (and (or transform-gradient?
                               transform-pattern?
                               transform-clippath?
                               transform-filter?
                               transform-mask?)
                           (= :group type))
                (update :className #(if % (dm/str % " svg-def") "svg-def")))
              (cond->
               transform-gradient?   (add-matrix :gradientTransform transform)
               transform-pattern?    (add-matrix :patternTransform transform)
               transform-clippath?   (add-matrix :transform transform)
               (or transform-filter?
                   transform-mask?)  (merge bounds)))

          ;; Fixes race condition with dynamic modifiers forcing redraw this properties before
          ;; the effect triggers
          attrs
          (cond-> attrs
            (or (= tag :filter) (= tag :mask))
            (merge {:data-old-x (:x attrs)
                    :data-old-y (:y attrs)
                    :data-old-width (:width attrs)
                    :data-old-height (:height attrs)}))

          [wrapper wrapper-props]
          (if (= tag :mask)
            ["g" #js {:className "svg-mask-wrapper"
                      :transform (str transform)}]
            [mf/Fragment #js {}])

          props
          (json/->js attrs :key-fn name)]

      [:> (name tag) props
       [:> wrapper wrapper-props
        (for [[index node] (d/enumerate content)]
          [:& svg-node {:key (dm/str "node-" index)
                        :type type
                        :node node
                        :prefix-id prefix-id
                        :transform transform
                        :bounds bounds}])]])))

(defn- get-svg-def-bounds
  [{:keys [tag attrs] :as node} shape transform]
  (if (or (= tag :mask) (contains? csvg/filter-tags tag))
    (some-> (grc/make-rect (d/parse-double (get attrs :x))
                           (d/parse-double (get attrs :y))
                           (d/parse-double (get attrs :width))
                           (d/parse-double (get attrs :height)))
            (gsh/transform-rect transform))
    (gsb/get-shape-filter-bounds shape)))

(mf/defc svg-defs
  {::mf/wrap-props false}
  [{:keys [shape render-id]}]
  (let [defs      (:svg-defs shape)

        transform (mf/with-memo [shape]
                    (if (= :svg-raw (:type shape))
                      (gmt/matrix)
                      (csvg/svg-transform-matrix shape)))

        ;; Paths doesn't have transform so we have to transform its gradients
        transform (if (some? (:svg-transform shape))
                    (gmt/multiply transform (:svg-transform shape))
                    transform)

        ;; FIXME: naming
        prefix-id (mf/use-fn
                   (mf/deps render-id defs)
                   (fn [id]
                     (cond->> id
                       (contains? defs id) (str render-id "-"))))]

    (for [[key node] defs]
      [:& svg-node {:key (dm/str key)
                    :type (:type shape)
                    :node node
                    :prefix-id prefix-id
                    :transform transform
                    :bounds (get-svg-def-bounds node shape transform)}])))
