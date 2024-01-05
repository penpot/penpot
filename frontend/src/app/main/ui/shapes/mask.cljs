;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.mask
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.main.ui.context :as muc]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn mask-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-mask"))

(defn mask-url [render-id mask]
  (dm/str "url(#" (mask-id render-id mask) ")"))

(defn clip-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-clip"))

(defn clip-url [render-id mask]
  (dm/str "url(#" (clip-id render-id mask) ")"))

(defn filter-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-filter"))

(defn filter-url [render-id mask]
  (dm/str "url(#" (filter-id render-id mask) ")"))

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

(defn- point->str
  [point]
  (dm/str (dm/get-prop point :x) "," (dm/get-prop point :y)))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [mask       (unchecked-get props "mask")
          render-id  (mf/use-ctx muc/render-id)

          svg-text?  (and ^boolean (cfh/text-shape? mask)
                          ^boolean (some? (:position-data mask)))

          points     (dm/get-prop mask :points)
          points-str (mf/with-memo [points]
                       (->> (map point->str points)
                            (str/join " ")))

          bounds     (mf/with-memo [points]
                       (grc/points->rect points))

          bx         (dm/get-prop bounds :x)
          by         (dm/get-prop bounds :y)
          bw         (dm/get-prop bounds :width)
          bh         (dm/get-prop bounds :height)

          shape      (mf/with-memo [mask]
                       (-> mask
                           (dissoc :shadow :blur)
                           (assoc :is-mask? true)))]

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
        [:polyline {:points points-str}]]

       ;; When te shape is a text we pass to the shape the info and disable the filter.
       ;; There is a bug in Firefox with filters and texts. We change the text to white at shape level
       [:mask {:class "mask-shape"
               :id (mask-id render-id mask)
               :x bx
               :y by
               :width bw
               :height bh

               ;; This is necesary to prevent a race condition in the dynamic-modifiers whether the modifier
               ;; triggers afte the render
               :data-old-x bx
               :data-old-y by
               :data-old-width bw
               :data-old-height bh
               :mask-units "userSpaceOnUse"}

        [:g {:filter (when-not ^boolean svg-text?
                       (filter-url render-id mask))}
         [:& shape-wrapper {:shape shape}]]]])))

