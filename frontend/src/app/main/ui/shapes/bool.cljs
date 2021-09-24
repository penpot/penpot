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

          ;;[content-a content-b]
          ;;(mf/use-memo
          ;; (mf/deps shape childs)
          ;; (fn []
          ;;   (let [childs (d/mapm #(gsh/transform-shape %2) childs)
          ;;         [content-a content-b]
          ;;         (->> (:shapes shape)
          ;;              (map #(get childs %))
          ;;              (filter #(not (:hidden %)))
          ;;              (map #(stp/convert-to-path % childs))
          ;;              (mapv :content)
          ;;              (mapv pb/add-previous))]
          ;;     (pb/content-intersect-split content-a content-b))))

          ;;_ (.log js/console "content-a" (clj->js content-a))
          ;;_ (.log js/console "content-b" (clj->js content-b))
          
          bool-content
          (mf/use-memo
           (mf/deps shape childs)
           (fn []
             (let [childs (d/mapm #(gsh/transform-shape %2) childs)]
               (->> (:shapes shape)
                    (map #(get childs %))
                    (filter #(not (:hidden %)))
                    (map #(stp/convert-to-path % childs))
                    (mapv :content)
                    (pb/content-bool (:bool-type shape))))))
          ]

      [:*
       [:& shape-wrapper {:shape (-> shape
                                     (assoc :type :path)
                                     (assoc :content bool-content))
                          :frame frame}]


       #_[:*
        [:g
         [:& shape-wrapper {:shape (-> shape
                                       (assoc :type :path)
                                       (assoc :stroke-color "blue")
                                       (assoc :stroke-opacity 1)
                                       (assoc :stroke-width 0.5)
                                       (assoc :stroke-style :solid)
                                       (dissoc :fill-color :fill-opacity)
                                       (assoc :content content-b))
                            :frame frame}]
         (for [{:keys [x y]} (app.common.geom.shapes.path/content->points content-b)]
           [:circle {:cx x
                     :cy y
                     :r 2.5
                     :style {:fill "blue"}}])]
        
        [:g
         [:& shape-wrapper {:shape (-> shape
                                       (assoc :type :path)
                                       (assoc :stroke-color "red")
                                       (assoc :stroke-opacity 1)
                                       (assoc :stroke-width 0.5)
                                       (assoc :stroke-style :solid)
                                       (dissoc :fill-color :fill-opacity)
                                       (assoc :content content-a))
                            :frame frame}]
         (for [{:keys [x y]} (app.common.geom.shapes.path/content->points content-a)]
           [:circle {:cx x
                     :cy y
                     :r 1.25
                     :style {:fill "red"}}])]]])))
