;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.image
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.data.images :as udi]
            [uxbox.main.geom :as geom]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.geom.matrix :as gmt]))

;; --- Refs

(defn image-ref
  [id]
  (-> (l/in [:images id])
      (l/derive st/state)))

;; --- Image Component

(declare image-shape)

(mx/defcs image-component
  {:mixins [mx/static mx/reactive]
   :will-mount (fn [own]
                 ;; TODO: maybe do it conditionally
                 ;; (only fetch when it's not fetched)
                 (when-let [id (-> own :rum/args first :image)]
                   (st/emit! (udi/fetch-image id)))
                 own)}
  [own {:keys [id image] :as shape}]
  (let [selected (mx/react common/selected-ref)
        image (mx/react (image-ref image))
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    (when image
      [:g.shape {:class (when selected? "selected")
                 :on-mouse-down on-mouse-down}
       (image-shape (assoc shape :image image))])))

;; --- Image Shape

(mx/defc image-shape
  {:mixins [mx/static]}
  [shape]
  (let [{:keys [id x1 y1 image
                width height
                tmp-resize-xform
                tmp-displacement]} (geom/size shape)

        xfmt (cond-> (or tmp-resize-xform (gmt/matrix))
               tmp-displacement (gmt/translate tmp-displacement))

        props {:x x1 :y y1
               :id (str "shape-" id)
               :preserve-aspect-ratio "none"
               :xlink-href (:url image)
               :transform (str xfmt)
               :width width
               :height height}

        attrs (merge props (attrs/extract-style-attrs shape))]
    [:image attrs]))
