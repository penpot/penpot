;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.group
  (:require [lentes.core :as l]
            [uxbox.store :as st]
            [uxbox.main.geom :as geom]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.shapes.rect :as rect]
            [uxbox.main.ui.shapes.circle :as circle]
            [uxbox.main.ui.shapes.text :as text]
            [uxbox.main.ui.shapes.path :as path]
            [uxbox.main.ui.shapes.image :as image]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Helpers

(declare group-component)

(defn- focus-shape
  [id]
  (-> (l/in [:shapes id])
      (l/derive st/state)))

(defn render-component
  [shape]
  (case (:type shape)
    :group (group-component shape)
    :text (text/text-component shape)
    :icon (icon/icon-component shape)
    :rect (rect/rect-component shape)
    :path (path/path-component shape)
    :image (image/image-component shape)
    :circle (circle/circle-component shape)))

(mx/defc component-container
  {:mixins [mx/reactive mx/static]}
  [id]
  (let [shape (mx/react (focus-shape id))]
    (when-not (:hidden shape)
      (render-component shape))))

;; --- Group Component

(declare group-shape)

(mx/defc group-component
  {:mixins [mx/static mx/reactive]}
  [{:keys [id x y width height group] :as shape}]
  (let [selected (mx/react common/selected-ref)
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape.group-shape
     {:class (when selected? "selected")
      :on-mouse-down on-mouse-down}
     (group-shape shape component-container)]))

;; --- Group Shape

(mx/defc group-shape
  {:mixins [mx/static mx/reactive]}
  [{:keys [id items tmp-resize-xform tmp-displacement] :as shape} factory]
  (let [xfmt (cond-> (or tmp-resize-xform (gmt/matrix))
               tmp-displacement (gmt/translate tmp-displacement))

        props {:id (str id) :transform (str xfmt)}

        attrs (merge props (attrs/extract-style-attrs shape))]
    [:g attrs
     (for [item (reverse items)
           :let [key (str item)]]
       (-> (factory item)
           (mx/with-key key)))]))

