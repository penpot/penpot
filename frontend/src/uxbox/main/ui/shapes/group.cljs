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
   [uxbox.util.interop :as itr]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]))

(defonce ^:dynamic *debug* (atom false))

(declare translate-to-frame)
(declare group-shape)

(defn group-wrapper [shape-wrapper]
  (mf/fnc group-wrapper
   {::mf/wrap-props false}
   [props]
   (let [shape (unchecked-get props "shape")
         frame (unchecked-get props "frame")
         on-mouse-down #(common/on-mouse-down % shape)
         on-context-menu #(common/on-context-menu % shape)
         children (-> (refs/objects-by-id (:shapes shape)) mf/deref)
         is-child-selected? (-> (refs/is-child-selected? (:id shape)) mf/deref)
         on-double-click
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (st/emit! (dw/select-inside-group
                      (:id shape)
                      @ms/mouse-position)))]

     [:g.shape {:on-mouse-down on-mouse-down
                :on-context-menu on-context-menu
                :on-double-click on-double-click}
      [:& (group-shape shape-wrapper) {:frame frame
                                       :shape (geom/transform-shape frame shape)
                                       :children children
                                       :is-child-selected? is-child-selected?}]])))

(defn group-shape [shape-wrapper]
  (mf/fnc group-shape
    {::mf/wrap-props false}
    [props]
    (let [frame (unchecked-get props "frame")
          shape (unchecked-get props "shape")
          children (unchecked-get props "children")
          is-child-selected? (unchecked-get props "is-child-selected?")
          {:keys [id x y width height rotation
                  displacement-modifier
                  resize-modifier]} shape

          transform (when (and rotation (pos? rotation))
                      (str/format "rotate(%s %s %s)"
                                  rotation
                                  (+ x (/ width 2))
                                  (+ y (/ height 2))))]
      [:g {:transform transform}
       (for [item children]
         [:& shape-wrapper {:frame frame
                            :shape (cond-> item
                                       displacement-modifier (assoc :displacement-modifier displacement-modifier)
                                       resize-modifier (assoc :resize-modifier resize-modifier))
                            :key (:id item)}])
       
       (when (not is-child-selected?)
         [:rect {:x x
                 :y y
                 :fill (if (deref *debug*) "red" "transparent")
                 :opacity 0.5
                 :id (str "group-" id)
                 :width width
                 :height height}])])))


