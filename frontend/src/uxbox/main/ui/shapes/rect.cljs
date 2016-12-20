;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.rect
  (:require [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]))

;; --- Rect Component

(declare rect-shape)

(mx/defc rect-component
  {:mixins [mx/reactive mx/static]}
  [shape]
  (let [{:keys [id x y width height group]} shape
        selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down
               }
     (rect-shape shape identity)]))

;; --- Rect Shape

(mx/defc rect-shape
  {:mixins [mx/static]}
  [shape]
  (let [{:keys [id x1 y1 width height
                tmp-resize-xform
                tmp-displacement]} (geom/size shape)

        xfmt (cond-> (or tmp-resize-xform (gmt/matrix))
               tmp-displacement (gmt/translate tmp-displacement))

        props {:x x1 :y y1  :id id
               :width width
               :height height
               :transform (str xfmt)}

        attrs (merge (attrs/extract-style-attrs shape) props)]
    [:rect attrs]))
