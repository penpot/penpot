;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.mask
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.common.geom.shapes :as gsh]))

(defn mask-str [mask]
  (str/fmt "url(#%s)" (str (:id mask) "-mask")))

(defn clip-str [mask]
  (str/fmt "url(#%s)" (str (:id mask) "-clip")))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [frame (unchecked-get props "frame")
          mask (unchecked-get props "mask")
          mask' (-> mask
                    (gsh/transform-shape)
                    (gsh/translate-to-frame frame))]
      [:defs
       [:filter {:id (str (:id mask) "-filter")}
        [:feFlood {:flood-color "white"
                   :result "FloodResult"}]
        [:feComposite {:in "FloodResult"
                       :in2 "SourceGraphic"
                       :operator "in"
                       :result "comp"}]]
       ;; Clip path is necesary so the elements inside the mask won't affect
       ;; the events outside. Clip hides the elements but mask doesn't (like display vs visibility)
       ;; we cannot use clips instead of mask because clips can only be simple shapes
       [:clipPath {:id (str (:id mask) "-clip")}
        [:polyline {:points (->> (:points mask')
                                 (map #(str (:x %) "," (:y %)))
                                 (str/join " "))}]]
       [:mask {:id (str (:id mask) "-mask")}
        [:g {:filter (str/fmt "url(#%s)" (str (:id mask) "-filter"))}
         [:& shape-wrapper {:frame frame
                            :shape (-> mask
                                       (dissoc :shadow :blur))}]]]])))

