;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.circle
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.data :refer [classnames normalize-props]]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

;; --- Circle Wrapper

(declare circle-shape)

(mf/defc circle-wrapper
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)]
    [:g.shape {:class (when selected? "selected")
               :on-mouse-down on-mouse-down}
     [:& circle-shape {:shape shape}]]))

;; --- Circle Shape

(mf/defc circle-shape
  [{:keys [shape] :as props}]
  (let [{:keys [id rotation cx cy modifier-mtx]} shape

        shape (cond
                (gmt/matrix? modifier-mtx) (geom/transform shape modifier-mtx)
                :else shape)

        center (gpt/point (:cx shape)
                          (:cy shape))

        rotation (or rotation 0)

        moving? (boolean modifier-mtx)

        transform (when (pos? rotation)
                    (str (-> (gmt/matrix)
                             (gmt/rotate rotation center))))

        props {:id (str "shape-" id)
               :class (classnames :move-cursor moving?)
               :transform transform}

        attrs (merge props
                     (attrs/extract-style-attrs shape)
                     (select-keys shape [:cx :cy :rx :ry]))]
    [:> :ellipse (normalize-props attrs)]))
