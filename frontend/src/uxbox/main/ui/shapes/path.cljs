;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.path
  (:require
   [cuerdas.core :as str :include-macros true]
   [rumext.alpha :as mf]
   [uxbox.main.data.workspace :as dw]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.interop :as itr]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.main.ui.shapes.bounding-box :refer [bounding-box]]
   [uxbox.main.ui.shapes.custom-stroke :refer [shape-custom-stroke]]))

;; --- Path Wrapper for workspace

(declare path-shape)

(mf/defc path-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down   (mf/use-callback
                         (mf/deps shape)
                         #(common/on-mouse-down % shape))
        on-context-menu (mf/use-callback
                         (mf/deps shape)
                         #(common/on-context-menu % shape))
        on-double-click (mf/use-callback
                         (mf/deps shape)
                         (fn [event]
                           (when selected?
                             (st/emit! (dw/start-edition-mode (:id shape))))))]

    [:g.shape {:on-double-click on-double-click
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& path-shape {:shape shape :background? true}]]))

;; --- Path Wrapper for viewer

(mf/defc path-viewer-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        {:keys [x y width height]} (geom/selection-rect-shape shape)
        show-interactions? (mf/deref refs/show-interactions?)
        on-mouse-down (mf/use-callback
                       (mf/deps shape)
                       #(common/on-mouse-down-viewer % shape))]
    [:g.shape {:on-mouse-down on-mouse-down
               :cursor (when (:interactions shape) "pointer")}
     [:*
       [:& path-shape {:shape shape}]
       (when (and (:interactions shape) show-interactions?)
         [:> "rect" #js {:x (- x 1)
                         :y (- y 1)
                         :width (+ width 2)
                         :height (+ height 2)
                         :fill "#31EFB8"
                         :stroke "#31EFB8"
                         :strokeWidth 1
                         :fillOpacity 0.2}])]]))

;; --- Path Shape

(defn- render-path
  [{:keys [segments close?] :as shape}]
  (let [numsegs (count segments)]
    (loop [buffer []
           index 0]
      (cond
        (>= index numsegs)
        (if close?
          (str/join " " (conj buffer "Z"))
          (str/join " " buffer))

        (zero? index)
        (let [{:keys [x y] :as segment} (nth segments index)
              buffer (conj buffer (str/istr "M~{x},~{y}"))]
          (recur buffer (inc index)))

        :else
        (let [{:keys [x y] :as segment} (nth segments index)
              buffer (conj buffer (str/istr "L~{x},~{y}"))]
          (recur buffer (inc index)))))))

(mf/defc path-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        background? (unchecked-get props "background?")
        {:keys [id x y width height]} (geom/shape->rect-shape shape)
        transform (geom/transform-matrix shape)
        pdata (render-path shape)
        props (-> (attrs/extract-style-attrs shape)
                  (itr/obj-assign!
                   #js {:transform transform
                        :id (str "shape-" id)
                        :d pdata}))]
    (if background?
      [:g
       [:path {:stroke "transparent"
               :fill "transparent"
               :stroke-width "20px"
               :d pdata}]
       [:& shape-custom-stroke {:shape shape
                                :base-props props
                                :elem-name "path"}]]
      [:& shape-custom-stroke {:shape shape
                               :base-props props
                               :elem-name "path"}])))

