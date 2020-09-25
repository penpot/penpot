;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.main.constants :as c]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.shapes.common :as common]
   [app.main.data.workspace.selection :as dws]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.filters :as filters]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.util.dom :as dom]
   [app.main.streams :as ms]
   [app.util.timers :as ts]))

(defn- frame-wrapper-factory-equals?
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

(defn make-selected-ref
  [id]
  (l/derived #(contains? % id) refs/selected-shapes))

(defn frame-wrapper-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % frame-wrapper-factory-equals?)
                  #(mf/deferred % ts/schedule-on-idle)]
       ::mf/wrap-props false}
      [props]
      (let [shape   (unchecked-get props "shape")
            objects (unchecked-get props "objects")

            selected-iref (mf/use-memo (mf/deps (:id shape))
                                       #(make-selected-ref (:id shape)))
            selected? (mf/deref selected-iref)
            zoom (mf/deref refs/selected-zoom)

            on-mouse-down   (mf/use-callback (mf/deps shape)
                                             #(common/on-mouse-down % shape))
            on-context-menu (mf/use-callback (mf/deps shape)
                                             #(common/on-context-menu % shape))

            shape (geom/transform-shape shape)
            {:keys [x y width height]} shape

            inv-zoom    (/ 1 zoom)
            children    (mapv #(get objects %) (:shapes shape))
            ds-modifier (get-in shape [:modifiers :displacement])

            label-pos (gpt/point x (- y (/ 10 zoom)))

            on-double-click
            (mf/use-callback
             (mf/deps (:id shape))
             (fn [event]
               (dom/prevent-default event)
               (st/emit! dw/deselect-all
                         (dw/select-shape (:id shape)))))

            on-mouse-over
            (mf/use-callback
             (mf/deps (:id shape))
             (fn []
               (st/emit! (dws/change-hover-state (:id shape) true))))

            on-mouse-out
            (mf/use-callback
             (mf/deps (:id shape))
             (fn []
               (st/emit! (dws/change-hover-state (:id shape) false))))

            filter-id (mf/use-memo filters/get-filter-id)]

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
                               (* zoom (:y label-pos))
                               ")")
                   ;; User may also select the frame with single click in the label
                   :on-click on-double-click
                   :on-mouse-over on-mouse-over
                   :on-mouse-out on-mouse-out}
            (:name shape)]
           [:g.frame {:filter (filters/filter-str filter-id shape)}
            [:& filters/filters {:filter-id filter-id :shape shape}]
            [:& frame-shape
             {:shape shape
              :childs children}]]])))))

