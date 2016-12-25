;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.circle
  (:require [lentes.core :as l]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Circle Component

(declare circle-shape)

(mx/defc circle-component
  {:mixins [mx/reactive mx/static]}
  [shape]
  (let [{:keys [id x y width height group]} shape
        selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     (circle-shape shape identity)]))

;; --- Circle Shape

(mx/defc circle-shape
  {:mixins [mx/static]}
  [{:keys [id tmp-resize-xform tmp-displacement rotation cx cy] :as shape}]
  (let [shape (cond-> shape
                tmp-displacement (geom/transform (gmt/translate-matrix tmp-displacement))
                tmp-resize-xform (geom/transform tmp-resize-xform))
        center (gpt/point (:cx shape)
                          (:cy shape))
        rotation (or rotation 0)

        xfmt (-> (gmt/matrix)
                 (gmt/rotate* rotation center))

        props {:id (str id) :transform (str xfmt)}
        attrs (merge props
                     (attrs/extract-style-attrs shape)
                     (select-keys shape [:cx :cy :rx :ry]))]
    [:ellipse attrs]))
