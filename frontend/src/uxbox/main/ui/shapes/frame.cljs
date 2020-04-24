;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.frame
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.util.dom :as dom]
   [uxbox.util.interop :as itr]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]))

(declare frame-wrapper)

(def frame-default-props {:fill-color "#ffffff"})

(declare frame-shape)
(declare translate-to-frame)

(defn frame-wrapper-memo-equals?
  [np op]
  (let [n-shape (aget np "shape")
        o-shape (aget op "shape")
        n-objs  (aget np "objects")
        o-objs  (aget op "objects")

        ids (:shapes n-shape)]
    (and (identical? n-shape o-shape)
         (loop [id (first ids)
                ids (rest ids)]
           (if (nil? id)
             true
             (if (identical? (get n-objs id)
                             (get o-objs id))
               (recur (first ids) (rest ids))
               false))))))

(defn frame-wrapper
  [shape-wrapper]
  (let [frame-shape (frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % frame-wrapper-memo-equals?)]
       ::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            objects (unchecked-get props "objects")

            selected-iref (mf/use-memo (mf/deps (:id shape))
                                       #(refs/make-selected (:id shape)))
            selected? (mf/deref selected-iref)
            zoom (mf/deref refs/selected-zoom)


            on-mouse-down   (mf/use-callback (mf/deps shape)
                                             #(common/on-mouse-down % shape))
            on-context-menu (mf/use-callback (mf/deps shape)
                                             #(common/on-context-menu % shape))


            {:keys [x y width height]} shape

            inv-zoom    (/ 1 zoom)
            childs      (mapv #(get objects %) (:shapes shape))
            ds-modifier (:displacement-modifier shape)

            label-pos (cond-> (gpt/point x (- y 10))
                        (gmt/matrix? ds-modifier) (gpt/transform ds-modifier))

            on-double-click
            (mf/use-callback
             (mf/deps (:id shape))
             (fn [event]
               (dom/prevent-default event)
               (st/emit! dw/deselect-all
                         (dw/select-shape (:id shape)))))]

        (when-not (:hidden shape)
          [:g {:class (when selected? "selected")
               :on-context-menu on-context-menu
               :on-double-click on-double-click
               :on-mouse-down on-mouse-down}
           [:text {:x 0
                   :y 0
                   :width width
                   :height 20
                   :class "workspace-frame-label"
                   ;; Ensure that the label has always the same font
                   ;; size, regardless of zoom
                   ;; https://css-tricks.com/transforms-on-svg-elements/
                   :transform (str
                               "scale(" inv-zoom ", " inv-zoom ") "
                               "translate(" (* zoom (:x label-pos)) ", "
                               (* zoom (:y label-pos)) ")")
                   ;; User may also select the frame with single click in the label
                   :on-click on-double-click}
            (:name shape)]
           [:& frame-shape
            {:shape (geom/transform-shape shape)
             :childs childs}]])))))

(defn frame-shape
  [shape-wrapper]
  (mf/fnc frame-shape
    {::mf/wrap-props false}
    [props]
    (let [childs (unchecked-get props "childs")
          shape (unchecked-get props "shape")
          {:keys [id x y width height]} shape

          props (-> (merge frame-default-props shape)
                    (attrs/extract-style-attrs)
                    (itr/obj-assign!
                     #js {:x 0
                          :y 0
                          :id (str "shape-" id)
                          :width width
                          :height height}))]
      [:svg {:x x :y y :width width :height height}
       [:> "rect" props]
       (for [[i item] (d/enumerate childs)]
         [:& shape-wrapper {:frame shape
                            :shape item
                            :key (:id item)}])])))

