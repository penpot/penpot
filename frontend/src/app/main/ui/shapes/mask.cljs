;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.mask
  (:require
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn mask-id [render-id mask]
  (str render-id "-" (:id mask) "-mask"))

(defn mask-url [render-id mask]
  (str "url(#" (mask-id render-id mask) ")"))

(defn clip-id [render-id mask]
  (str render-id "-" (:id mask) "-clip"))

(defn clip-url [render-id mask]
  (str "url(#" (clip-id render-id mask) ")"))

(defn filter-id [render-id mask]
  (str render-id "-" (:id mask) "-filter"))

(defn filter-url [render-id mask]
  (str "url(#" (filter-id render-id mask) ")"))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [frame     (unchecked-get props "frame")
          mask      (unchecked-get props "mask")
          render-id (mf/use-ctx muc/render-ctx)

          mask' (-> mask
                    (gsh/transform-shape)
                    (gsh/translate-to-frame frame))]
      [:defs
       [:filter {:id (filter-id render-id mask)}
        [:feFlood {:flood-color "white"
                   :result "FloodResult"}]
        [:feComposite {:in "FloodResult"
                       :in2 "SourceGraphic"
                       :operator "in"
                       :result "comp"}]]
       ;; Clip path is necessary so the elements inside the mask won't affect
       ;; the events outside. Clip hides the elements but mask doesn't (like display vs visibility)
       ;; we cannot use clips instead of mask because clips can only be simple shapes
       [:clipPath {:id (clip-id render-id mask)}
        [:polyline {:points (->> (:points mask')
                                 (map #(str (:x %) "," (:y %)))
                                 (str/join " "))}]]
       [:mask {:id (mask-id render-id mask)}
        [:g {:filter (filter-url render-id mask)}
         [:& shape-wrapper {:frame frame
                            :shape (-> mask
                                       (dissoc :shadow :blur))}]]]])))

