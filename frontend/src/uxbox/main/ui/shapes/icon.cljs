;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.icon
  (:require [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.geom :as geom]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.geom.matrix :as gmt]))

;; --- Icon Component

(declare icon-shape)

(mx/defc icon-component
  {:mixins [mx/static mx/reactive]}
  [{:keys [id] :as shape}]
  (let [selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     (icon-shape shape identity)]))

;; --- Icon Shape

(mx/defc icon-shape
  {:mixins [mx/static]}
  [shape]
  (let [{:keys [x1 y1 content id metadata
                width height
                tmp-resize-xform
                tmp-displacement]} (geom/size shape)

        [_ _ orw orh] (:view-box metadata)
        scalex (/ width orw)
        scaley (/ height orh)

        view-box (apply str (interpose " " (:view-box metadata)))

        xfmt (cond-> (or tmp-resize-xform (gmt/matrix))
               tmp-displacement (gmt/translate tmp-displacement)
               true (gmt/translate x1 y1)
               true (gmt/scale scalex scaley))

        props {:id (str id)
               :preserve-aspect-ratio "none"
               :dangerouslySetInnerHTML {:__html content}
               :transform (str xfmt)}

        attrs (merge props (attrs/extract-style-attrs shape))]
    [:g attrs]))

;; --- Icon SVG

(mx/defc icon-svg
  {:mixins [mx/static]}
  [{:keys [content id metadata] :as shape}]
  (let [view-box (apply str (interpose " " (:view-box metadata)))
        props {:view-box view-box
               :id (str id)
               :dangerouslySetInnerHTML {:__html content}}]
    [:svg props]))
