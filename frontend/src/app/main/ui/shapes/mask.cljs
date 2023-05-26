;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.mask
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.main.ui.context :as muc]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

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
              (assoc :fills [{:fill-color "#FFFFFF" :fill-opacity 1}])))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [mask        (unchecked-get props "mask")
          render-id   (mf/use-ctx muc/render-id)
          svg-text?   (and (= :text (:type mask)) (some? (:position-data mask)))

          mask-bb      (:points mask)
          mask-bb-rect (grc/points->rect mask-bb)]
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
                                 (map #(dm/str (:x %) "," (:y %)))
                                 (str/join " "))}]]

       ;; When te shape is a text we pass to the shape the info and disable the filter.
       ;; There is a bug in Firefox with filters and texts. We change the text to white at shape level
       [:mask {:class "mask-shape"
               :id (mask-id render-id mask)
               :x (:x mask-bb-rect)
               :y (:y mask-bb-rect)
               :width (:width mask-bb-rect)
               :height (:height mask-bb-rect)

               ;; This is necesary to prevent a race condition in the dynamic-modifiers whether the modifier
               ;; triggers afte the render
               :data-old-x (:x mask-bb-rect)
               :data-old-y (:y mask-bb-rect)
               :data-old-width (:width mask-bb-rect)
               :data-old-height (:height mask-bb-rect)
               :mask-units "userSpaceOnUse"}
        [:g {:filter (when-not svg-text? (filter-url render-id mask))}
         [:& shape-wrapper {:shape (-> mask (dissoc :shadow :blur) (assoc :is-mask? true))}]]]])))

