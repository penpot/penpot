;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.mask
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gst]
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

(defn set-white-fill
  [shape]
  (let [update-color
        (fn [data]
          (-> data
              (dissoc :fill-color :fill-opacity :fill-color-gradient)
              (assoc :fill-color "#FFFFFF" :fill-opacity 1)))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [mask      (unchecked-get props "mask")
          render-id (mf/use-ctx muc/render-ctx)
          svg-text? (and (= :text (:type mask)) (some? (:position-data mask)))

          mask      (cond-> mask svg-text? set-white-fill)

          mask-bb
          (cond
            svg-text?
            (gst/position-data-points mask)

            :else
            (-> (gsh/transform-shape mask)
                (:points)))]
      [:*
       [:g {:opacity 0}
        [:g {:id (str "shape-" (mask-id render-id mask))}
         [:& shape-wrapper {:shape (dissoc mask :shadow :blur)}]]]

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
        [:clipPath {:class "mask-clip-path"
                    :id (clip-id render-id mask)}
         [:polyline {:points (->> mask-bb
                                  (map #(str (:x %) "," (:y %)))
                                  (str/join " "))}]]

        [:mask {:class "mask-shape"
                :id (mask-id render-id mask)}
         ;; SVG texts are broken in Firefox with the filter. When the masking shapes is a text
         ;; we use the `set-white-fill` instead of using the filter
         [:g {:filter (when-not svg-text? (filter-url render-id mask))}
          [:use {:href (str "#shape-" (mask-id render-id mask))}]]]]])))

