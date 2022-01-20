;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(defn check-frame-props
  "Checks for changes in the props of a frame"
  [new-props old-props]
  (let [new-shape (unchecked-get new-props "shape")
        old-shape (unchecked-get old-props "shape")

        new-thumbnail? (unchecked-get new-props "thumbnail?")
        old-thumbnail? (unchecked-get old-props "thumbnail?")

        new-objects (unchecked-get new-props "objects")
        old-objects (unchecked-get old-props "objects")

        new-children (->> new-shape :shapes (mapv #(get new-objects %)))
        old-children (->> old-shape :shapes (mapv #(get old-objects %)))]
    (and (= new-shape old-shape)
         (= new-thumbnail? old-thumbnail?)
         (= new-children old-children))))

(mf/defc frame-placeholder
  {::mf/wrap-props false}
  [props]
  (let [{:keys [x y width height fill-color] :as shape} (obj/get props "shape")]
    (if (some? (:thumbnail shape))
      [:& frame/frame-thumbnail {:shape shape}]
      [:rect {:x x :y y :width width :height height :style {:fill (or fill-color "var(--color-white)")}}])))

(defn custom-deferred
  [component]
  (mf/fnc deferred
    {::mf/wrap-props false}
    [props]
    (let [shape (-> (obj/get props "shape")
                    (select-keys [:x :y :width :height])
                    (hooks/use-equal-memo))

          tmp (mf/useState false)
          ^boolean render? (aget tmp 0)
          ^js set-render (aget tmp 1)
          prev-shape-ref (mf/use-ref shape)]

      (mf/use-effect
       (mf/deps shape)
       (fn []
         (mf/set-ref-val! prev-shape-ref shape)
         (set-render false)))

      (mf/use-effect
       (mf/deps render? shape)
       (fn []
         (when-not render?
           (let [sem (ts/schedule-on-idle #(set-render true))]
             #(rx/dispose! sem)))))

      (if (and render? (= shape (mf/ref-val prev-shape-ref)))
        (mf/create-element component props)
        (mf/create-element frame-placeholder props)))))

;; Draw the frame proper as a deferred component
(defn deferred-frame-shape-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc defered-frame-wrapper
      {::mf/wrap-props false
       ::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "childs"]))
                  custom-deferred]}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (unchecked-get props "childs")]
        [:& frame-shape {:shape shape
                         :childs childs}]))))

(defn frame-wrapper-factory
  [shape-wrapper]
  (let [deferred-frame-shape (deferred-frame-shape-factory shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % check-frame-props)]
       ::mf/wrap-props false}
      [props]

      (when-let [shape (unchecked-get props "shape")]
        (let [objects    (unchecked-get props "objects")
              thumbnail? (unchecked-get props "thumbnail?")

              children
              (-> (mapv (d/getf objects) (:shapes shape))
                  (hooks/use-equal-memo))

              all-children
              (-> (cp/get-children-objects (:id shape) objects)
                  (hooks/use-equal-memo))

              show-thumbnail?
              (and thumbnail? (some? (:thumbnail shape)))]

          [:g.frame-wrapper {:display (when (:hidden shape) "none")}
           [:> shape-container {:shape shape}
            [:& ff/fontfaces-style {:shapes all-children}]
            (if show-thumbnail?
              [:& frame/frame-thumbnail {:shape shape}]
              [:& deferred-frame-shape
               {:shape shape
                :childs children}])]])))))

