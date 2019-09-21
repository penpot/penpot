;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.canvas :as canvas]
   [uxbox.main.ui.shapes.text :as text]))

(defn render-shape
  [shape]
  (mf/html
   (case (:type shape)
     :canvas [:& canvas/canvas-component {:shape shape}]
     :curve [:& path/path-component {:shape shape}]
     :text [:& text/text-component {:shape shape}]
     :icon [:& icon/icon-component {:shape shape}]
     :rect [:& rect/rect-component {:shape shape}]
     :path [:& path/path-component {:shape shape}]
     :image [:& image/image-component {:shape shape}]
     :circle [:& circle/circle-component {:shape shape}])))

(mf/defc render-shape'
  {:wrap [mf/wrap-memo]}
  [{:keys [shape] :as props}]
  (render-shape shape))

(mf/defc shape-component
  {:wrap [mf/wrap-memo]}
  [{:keys [shape] :as props}]
  (when (and shape (not (:hidden shape)))
    [:& render-shape' {:shape shape}]))

(mf/defc canvas-and-shapes
  {:wrap [mf/wrap-memo]}
  [{:keys [page] :as props}]
  (let [shapes-by-id (mf/deref refs/shapes-by-id)
        shapes (map #(get shapes-by-id %) (:shapes page []))
        canvas (map #(get shapes-by-id %) (:canvas page []))]
    [:*
     (for [item canvas]
       [:& shape-component {:shape item :key (:id item)}])
     (for [item shapes]
       [:& shape-component {:shape item :key (:id item)}])]))


