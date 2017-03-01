;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.circle
  (:require [lentes.core :as l]
            [uxbox.main.refs :as refs]
            [uxbox.main.geom :as geom]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.util.data :refer [classnames]]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Circle Component

(declare circle-shape)

(mx/defc circle-component
  {:mixins [mx/reactive mx/static]}
  [{:keys [id] :as shape}]
  (let [modifiers (mx/react (refs/selected-modifiers id))
        selected (mx/react refs/selected-shapes)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)
        shape (assoc shape :modifiers modifiers)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     (circle-shape shape)]))

;; --- Circle Shape

(mx/defc circle-shape
  {:mixins [mx/static]}
  [{:keys [id modifiers rotation cx cy] :as shape}]
  (let [{:keys [resize displacement]} modifiers

        shape (cond-> shape
                displacement (geom/transform displacement)
                resize (geom/transform resize))

        center (gpt/point (:cx shape)
                          (:cy shape))
        rotation (or rotation 0)

        moving? (boolean displacement)

        xfmt (-> (gmt/matrix)
                 (gmt/rotate* rotation center))

        props {:id (str "shape-" id)
               :class (classnames :move-cursor moving?)
               :transform (str xfmt)}

        attrs (merge props
                     (attrs/extract-style-attrs shape)
                     (select-keys shape [:cx :cy :rx :ry]))]
    [:ellipse attrs]))
