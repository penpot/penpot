;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.shapes.group
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.util.dom :as dom]
   [uxbox.util.interop :as itr]
   [uxbox.util.debug :refer [debug?]]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.shapes.bounding-box :refer [bounding-box]]))

(defn- equals?
  [np op]
  (let [n-shape (unchecked-get np "shape")
        o-shape (unchecked-get op "shape")
        n-frame (unchecked-get np "frame")
        o-frame (unchecked-get op "frame")]
    (and (= n-frame o-frame)
         (= n-shape o-shape))))

(declare translate-to-frame)
(declare group-shape)

(defn group-wrapper
  [shape-wrapper]
  (let [group-shape (group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      {::mf/wrap [#(mf/memo' % equals?)]
       ::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")
            on-mouse-down   (mf/use-callback (mf/deps shape)
                                             #(common/on-mouse-down % shape))
            on-context-menu (mf/use-callback (mf/deps shape)
                                             #(common/on-context-menu % shape))

            children-ref   (mf/use-memo (mf/deps shape)
                                        #(refs/objects-by-id (:shapes shape)))
            children       (mf/deref children-ref)


            is-child-selected-ref (mf/use-memo (mf/deps (:id shape))
                                               #(refs/is-child-selected? (:id shape)))
            is-child-selected?    (mf/deref is-child-selected-ref)

            on-double-click
            (mf/use-callback
             (mf/deps (:id shape))
             (fn [event]
               (dom/stop-propagation event)
               (dom/prevent-default event)
               (st/emit! (dw/select-inside-group (:id shape) @ms/mouse-position))))]

        [:g.shape
         {:on-mouse-down on-mouse-down
          :on-context-menu on-context-menu
          :on-double-click on-double-click}

         [:& group-shape
          {:frame frame
           :shape (geom/transform-shape frame shape)
           :children children
           :is-child-selected? is-child-selected?}]
         [:& bounding-box {:shape shape :frame frame}]]))))

(defn group-shape
  [shape-wrapper]
  (mf/fnc group-shape
    {::mf/wrap-props false}
    [props]
    (let [frame (unchecked-get props "frame")
          shape (unchecked-get props "shape")
          children (unchecked-get props "children")
          is-child-selected? (unchecked-get props "is-child-selected?")
          {:keys [id x y width height]} shape
          transform (geom/transform-matrix shape)]
      [:g
       (for [item children]
         [:& shape-wrapper
          {:frame frame :shape item :key (:id item)}])

       (when (not is-child-selected?)
         [:rect {:transform transform
                 :x x
                 :y y
                 :fill (if (debug? :group) "red" "transparent")
                 :opacity 0.5
                 :id (str "group-" id)
                 :width width
                 :height height}])])))


