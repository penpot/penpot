;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.image
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.http :as http]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.data.images :as udi]
            [uxbox.main.geom :as geom]))

;; --- Refs

(defn image-ref
  [id]
  (-> (l/in [:images id])
      (l/derive st/state)))

;; --- Image Component

(declare image-shape)

(defn- will-mount
  [own]
  (let [{:keys [image]} (first (:rum/args own))]
    (println (:rum/args own))
    (rs/emit! (udi/fetch-image image))
    own))

(mx/defcs image-component
  {:mixins [mx/static mx/reactive]
   :will-mount will-mount}
  [own {:keys [id image] :as shape}]
  (let [selected (mx/react common/selected-ref)
        image (mx/react (image-ref image))
        selected? (contains? selected id)
        local (:rum/local own)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    (when image
      [:g.shape {:class (when selected? "selected")
                 :on-mouse-down on-mouse-down}
       (image-shape (assoc shape :image image))])))

;; --- Image Shape

(mx/defc image-shape
  {:mixins [mx/static]}
  [{:keys [id x1 y1 image] :as shape}]
  (let [key (str "shape-" id)
        ;; rfm (geom/transformation-matrix shape)
        size (geom/size shape)
        props {:x x1 :y y1 :id key :key key
               :preserve-aspect-ratio "none"
               :xlink-href (:url image)}
        attrs (-> (attrs/extract-style-attrs shape)
                  (merge props size))]
    [:image attrs]))
