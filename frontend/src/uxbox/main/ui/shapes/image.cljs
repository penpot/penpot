;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.image
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.data.images :as udi]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.data :refer [classnames normalize-props]]
   [uxbox.util.geom.matrix :as gmt]))

;; --- Refs

(defn image-ref
  [id]
  (-> (l/in [:images id])
      (l/derive st/state)))

;; --- Image Component

(declare image-shape)

(mf/defc image-component
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        image (mf/deref (image-ref (:image shape)))
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)]

    (mf/use-effect #(st/emit! (udi/fetch-image (:image shape))))

    (when image
      [:g.shape {:class (when selected? "selected")
                 :on-mouse-down on-mouse-down}
       [:& image-shape {:shape shape
                        :image image}]])))

;; --- Image Shape

(mf/defc image-shape
  [{:keys [shape image] :as props}]
  (let [{:keys [id x1 y1 width height modifier-mtx]} (geom/size shape)
        moving? (boolean modifier-mtx)
        transform (when (gmt/matrix? modifier-mtx)
                    (str modifier-mtx))

        props {:x x1 :y y1
               :id (str "shape-" id)
               :preserve-aspect-ratio "none"
               :class (classnames :move-cursor moving?)
               :xlink-href (:url image)
               :transform transform
               :width width
               :height height}
        attrs (merge props (attrs/extract-style-attrs shape))]
    [:> :image (normalize-props attrs)]))
