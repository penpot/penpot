;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.group
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.interop :as itr]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.shapes.attrs :as attrs]))

(declare translate-to-frame)
(declare group-shape)

(defn group-wrapper [shape-wrapper]
  (mf/fnc group-wrapper
   {::mf/wrap-props false}
   [props]
   (let [shape (unchecked-get props "shape")
         on-mouse-down #(common/on-mouse-down % shape)
         on-context-menu #(common/on-context-menu % shape)
         objects (-> refs/objects mf/deref)
         children (mapv #(get objects %) (:shapes shape))
         frame (get objects (:frame-id shape))]
     [:g.shape {:on-mouse-down on-mouse-down
                :on-context-menu on-context-menu}
      [:& (group-shape shape-wrapper) {:shape shape
                                       :shape-wrapper shape-wrapper
                                       :children children
                                       :frame frame }]])))

(defn group-shape [shape-wrapper]
  (mf/fnc group-shape
    {::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")
          children (unchecked-get props "children")
          frame (unchecked-get props "frame")

          ds-modifier (:displacement-modifier shape)
          rz-modifier (:resize-modifier shape)

          shape (cond-> shape
                  (and (= "root" (:name frame)) (gmt/matrix? rz-modifier)) (geom/transform rz-modifier)
                  (gmt/matrix? rz-modifier) (geom/transform ds-modifier))

          {:keys [id x y width height rotation]} shape

          transform (when (and rotation (pos? rotation))
                      (str/format "rotate(%s %s %s)"
                                  rotation
                                  (+ x (/ width 2))
                                  (+ y (/ height 2))))]
      [:g
       (for [item (reverse children)]
         [:& shape-wrapper {:shape (-> item
                                       (geom/transform rz-modifier)
                                       (assoc :displacement-modifier ds-modifier)
                                       (translate-to-frame frame))
                            :key (:id item)}])
       
       [:rect {:x x
               :y y
               :fill "red"
               :opacity 0.8
               :transform transform
               :id (str "group-" id)
               :width width
               :height height}]])))

(defn- translate-to-frame
  [shape frame]
  (let [pt (gpt/point (- (:x frame)) (- (:y frame)))
        frame-ds-modifier (:displacement-modifier frame)
        rz-modifier (:resize-modifier shape)
        shape (cond-> shape
                (gmt/matrix? frame-ds-modifier)
                (geom/transform frame-ds-modifier)

                (and (= (:type shape) :group) (gmt/matrix? rz-modifier))
                (geom/transform rz-modifier)
                
                (gmt/matrix? rz-modifier)
                (-> (geom/transform rz-modifier)
                    (dissoc :resize-modifier)))]
    (geom/move shape pt)))

