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

(mf/defc shape-wrapper
  {:wrap [mf/wrap-memo]}
  [{:keys [shape] :as props}]
  (when (and shape (not (:hidden shape)))
    (case (:type shape)
      :canvas [:& canvas/canvas-wrapper {:shape shape}]
      :curve [:& path/path-wrapper {:shape shape}]
      :text [:& text/text-wrapper {:shape shape}]
      :icon [:& icon/icon-wrapper {:shape shape}]
      :rect [:& rect/rect-wrapper {:shape shape}]
      :path [:& path/path-wrapper {:shape shape}]
      :image [:& image/image-wrapper {:shape shape}]
      :circle [:& circle/circle-wrapper {:shape shape}])))

(mf/defc canvas-and-shapes
  {:wrap [mf/wrap-memo]}
  [{:keys [page] :as props}]
  (let [shapes-by-id (mf/deref refs/shapes-by-id)
        shapes (map #(get shapes-by-id %) (:shapes page []))
        canvas (map #(get shapes-by-id %) (:canvas page []))]
    [:*
     (for [item canvas]
       [:& shape-wrapper {:shape item :key (:id item)}])
     (for [item shapes]
       [:& shape-wrapper {:shape item :key (:id item)}])]))


