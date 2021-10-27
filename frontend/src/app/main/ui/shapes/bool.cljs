;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.common.path.bool :as pb]
   [app.common.path.shapes-to-path :as stp]
   [app.main.ui.hooks :refer [use-equal-memo]]
   [app.main.ui.shapes.export :as use]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc debug-bool
  {::mf/wrap-props false}
  [props]

  (let [frame  (obj/get props "frame")
        shape  (obj/get props "shape")
        childs (obj/get props "childs")

        [content-a content-b]
        (mf/use-memo
         (mf/deps shape childs)
         (fn []
           (let [childs (d/mapm #(-> %2 (gsh/translate-to-frame frame) gsh/transform-shape) childs)
                 [content-a content-b]
                 (->> (:shapes shape)
                      (map #(get childs %))
                      (filter #(not (:hidden %)))
                      (map #(stp/convert-to-path % childs))
                      (map :content)
                      (map pb/close-paths)
                      (map pb/add-previous))]
             (pb/content-intersect-split content-a content-b))))]
    [:g.debug-bool
     [:g.shape-a
      [:& path-shape {:shape (-> shape
                                 (assoc :type :path)
                                 (assoc :stroke-color "blue")
                                 (assoc :stroke-opacity 1)
                                 (assoc :stroke-width 1)
                                 (assoc :stroke-style :solid)
                                 (dissoc :fill-color :fill-opacity)
                                 (assoc :content content-b))
                      :frame frame}]
      (for [{:keys [x y]} (gsp/content->points (pb/close-paths content-b))]
        [:circle {:cx x
                  :cy y
                  :r 2.5
                  :style {:fill "blue"}}])]

     [:g.shape-b
      [:& path-shape {:shape (-> shape
                                 (assoc :type :path)
                                 (assoc :stroke-color "red")
                                 (assoc :stroke-opacity 1)
                                 (assoc :stroke-width 0.5)
                                 (assoc :stroke-style :solid)
                                 (dissoc :fill-color :fill-opacity)
                                 (assoc :content content-a))
                      :frame frame}]
      (for [{:keys [x y]} (gsp/content->points (pb/close-paths content-a))]
        [:circle {:cx x
                  :cy y
                  :r 1.25
                  :style {:fill "red"}}])]])
  )


(defn bool-shape
  [shape-wrapper]
  (mf/fnc bool-shape
          {::mf/wrap-props false}
          [props]
          (let [frame  (obj/get props "frame")
                shape  (obj/get props "shape")
                childs (obj/get props "childs")

                childs (use-equal-memo childs)

                include-metadata? (mf/use-ctx use/include-metadata-ctx)

                bool-content
                (mf/use-memo
                 (mf/deps shape childs)
                 (fn []
                   (let [childs (d/mapm #(-> %2 gsh/transform-shape (gsh/translate-to-frame frame)) childs)]
                     (->> (:shapes shape)
                          (map #(get childs %))
                          (filter #(not (:hidden %)))
                          (map #(stp/convert-to-path % childs))
                          (mapv :content)
                          (pb/content-bool (:bool-type shape))))))]

            [:*
             [:& path-shape {:shape (assoc shape :content bool-content)}]

             (when include-metadata?
               [:> "penpot:bool" {}
                (for [item (->> (:shapes shape) (mapv #(get childs %)))]
                  [:& shape-wrapper {:frame frame
                                     :shape item
                                     :key (:id item)}])])

             #_[:& debug-bool {:frame frame
                             :shape shape
                             :childs childs}]])))
