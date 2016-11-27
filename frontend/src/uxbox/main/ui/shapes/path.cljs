;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.path
  (:require [uxbox.util.mixins :as mx :include-macros true]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.geom :as geom]))

;; --- Path Component

(declare path-shape)

(mx/defc path-component
  {:mixins [mx/static mx/reactive]}
  [{:keys [id] :as shape}]
  (let [selected (mx/react common/selected-ref)
        selected? (contains? selected id)]
    (letfn [(on-mouse-down [event]
              (common/on-mouse-down event shape selected))
            (on-double-click [event]
              (when selected?
                (st/emit! (uds/start-edition-mode id))))]
      [:g.shape {:class (when selected? "selected")
                 :on-double-click on-double-click
                 :on-mouse-down on-mouse-down}
       (path-shape shape identity)])))

;; --- Path Shape

(defn- render-path
  [{:keys [points close?] :as shape}]
  {:pre [(pos? (count points))]}
  (let [start (first points)
        init  (str "M " (:x start) " " (:y start))
        path  (reduce #(str %1 " L" (:x %2) " " (:y %2)) init points)]
    (cond-> path
      close? (str " Z"))))

(mx/defc path-shape
  {:mixins [mx/static]}
  [{:keys [id drawing?] :as shape}]
  (let [key (str "shape-" id)
        rfm (geom/transformation-matrix shape)
        attrs (-> (attrs/extract-style-attrs shape)
                  (merge {:id key :key key :d (render-path shape)})
                  (merge (when-not drawing?
                           #_{:transform (str rfm)})))]
    [:path attrs]))
