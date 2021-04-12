;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.svg-defs
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [rumext.alpha :as mf]))

(defn add-matrix [attrs transform-key transform-matrix]
  (update attrs
          transform-key
          (fn [val]
            (if val
              (str transform-matrix " " val)
              (str transform-matrix)))))

(defn transform-region [attrs transform]
  (let [{x-str :x y-str :y width-str :width height-str :height} attrs
        data (map d/parse-double [x-str y-str width-str height-str])]
    (if (every? (comp not nil?) data)
      (let [[x y width height] data
            p1 (-> (gpt/point x y)
                   (gpt/transform transform))
            p2 (-> (gpt/point (+ x width) (+ y height))
                   (gpt/transform transform))]

        (assoc attrs
               :x      (:x p1)
               :y      (:y p1)
               :width  (- (:x p2) (:x p1))
               :height (- (:y p2) (:y p1))))
      attrs)))

(mf/defc svg-node [{:keys [node prefix-id transform]}]
  (cond
    (string? node) node

    :else
    (let [{:keys [tag attrs content]} node

          transform-gradient? (and (contains? usvg/gradient-tags tag)
                                   (= "userSpaceOnUse" (get attrs :gradientUnits "objectBoundingBox")))

          transform-pattern?  (and (= :pattern tag)
                                   (= "userSpaceOnUse" (get attrs :patternUnits "userSpaceOnUse")))

          transform-clippath? (and (= :clipPath tag)
                                   (= "userSpaceOnUse" (get attrs :clipPathUnits "userSpaceOnUse")))

          transform-filter?   (and (contains? usvg/filter-tags tag)
                                   (= "userSpaceOnUse" (get attrs :filterUnits "objectBoundingBox")))

          transform-mask?     (and (= :mask tag)
                                   (= "userSpaceOnUse" (get attrs :maskUnits "objectBoundingBox")))

          attrs (-> attrs
                    (usvg/update-attr-ids prefix-id)
                    (usvg/clean-attrs)

                    (cond->
                        transform-gradient?   (add-matrix :gradientTransform transform)
                        transform-pattern?    (add-matrix :patternTransform transform)
                        transform-clippath?   (add-matrix :transform transform)
                        (or transform-filter?
                            transform-mask?)  (transform-region transform)))

          [wrapper wrapper-props] (if (= tag :mask)
                                    ["g" #js {:transform (str transform)}]
                                    [mf/Fragment (obj/new)])]

      [:> (name tag) (clj->js attrs)
       [:> wrapper wrapper-props
        (for [node content] [:& svg-node {:node node
                                          :prefix-id prefix-id
                                          :transform transform}])]])))

(mf/defc svg-defs [{:keys [shape render-id]}]
  (let [svg-defs (:svg-defs shape)

        transform (mf/use-memo
                   (mf/deps shape)
                   #(if (= :svg-raw (:type shape))
                      (gmt/matrix)
                      (usvg/svg-transform-matrix shape)))

        ;; Paths doesn't have transform so we have to transform its gradients
        transform (if (contains? shape :svg-transform)
                    (gmt/multiply transform (or (:svg-transform shape) (gmt/matrix)))
                    transform)

        prefix-id
        (fn [id]
          (cond->> id
            (contains? svg-defs id) (str render-id "-")))]

    (when (and svg-defs (not (empty? svg-defs)))
      (for [svg-def (vals svg-defs)]
        [:& svg-node {:node svg-def
                      :prefix-id prefix-id
                      :transform transform}]))))

