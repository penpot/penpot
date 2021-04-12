; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.widgets
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.shapes.path.actions :refer [path-actions]]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc pixel-grid
  [{:keys [vbox zoom]}]
  [:g.pixel-grid
   [:defs
    [:pattern {:id "pixel-grid"
               :viewBox "0 0 1 1"
               :width 1
               :height 1
               :pattern-units "userSpaceOnUse"}
     [:path {:d "M 1 0 L 0 0 0 1"
             :style {:fill "none"
                     :stroke "#59B9E2"
                     :stroke-opacity "0.2"
                     :stroke-width (str (/ 1 zoom))}}]]]
   [:rect {:x (:x vbox)
           :y (:y vbox)
           :width (:width vbox)
           :height (:height vbox)
           :fill (str "url(#pixel-grid)")
           :style {:pointer-events "none"}}]])

(mf/defc viewport-actions
  {::mf/wrap [mf/memo]}
  []
  (let [edition (mf/deref refs/selected-edition)
        selected (mf/deref refs/selected-objects)
        shape (-> selected first)]
    (when (and (= (count selected) 1)
               (= (:id shape) edition)
               (= :path (:type shape)))
      [:div.viewport-actions
       [:& path-actions {:shape shape}]])))

(mf/defc cursor-tooltip
  [{:keys [zoom tooltip] :as props}]
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom)))
        pos-x (- (:x coords) 100)
        pos-y (+ (:y coords) 30)]
    [:g {:transform (str "translate(" pos-x "," pos-y ")")}
     [:foreignObject {:width 200 :height 100 :style {:text-align "center"}}
      [:span tooltip]]]))

(mf/defc selection-rect
  {:wrap [mf/memo]}
  [{:keys [data] :as props}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :width (:width data)
      :height (:height data)}]))

;; Ensure that the label has always the same font
;; size, regardless of zoom
;; https://css-tricks.com/transforms-on-svg-elements/
(defn text-transform
  [{:keys [x y]} zoom]
  (let [inv-zoom (/ 1 zoom)]
    (str
     "scale(" inv-zoom ", " inv-zoom ") "
     "translate(" (* zoom x) ", " (* zoom y) ")")))

(mf/defc frame-title
  [{:keys [frame modifiers selected? zoom on-frame-enter on-frame-leave on-frame-select]}]
  (let [{:keys [width x y]} (gsh/transform-shape frame)
        label-pos  (gpt/point x (- y (/ 10 zoom)))

        on-mouse-down
        (mf/use-callback
         (mf/deps (:id frame) on-frame-select)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (on-frame-select event (:id frame))))

        on-double-click
        (mf/use-callback
          (mf/deps (:id frame))
          (st/emitf (dw/go-to-layout :layers)
                    (dw/start-rename-shape (:id frame))))

        on-pointer-enter
        (mf/use-callback
         (mf/deps (:id frame) on-frame-enter)
         (fn [event]
           (on-frame-enter (:id frame))))

        on-pointer-leave
        (mf/use-callback
         (mf/deps (:id frame) on-frame-leave)
         (fn [event]
           (on-frame-leave (:id frame))))]

    [:text {:x 0
            :y 0
            :width width
            :height 20
            :class "workspace-frame-label"
            :transform (str (when (and selected? modifiers)
                              (str (:displacement modifiers) " " ))
                            (text-transform label-pos zoom))
            :style {:fill (when selected? "#28c295")}
            :on-mouse-down on-mouse-down
            :on-double-click on-double-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
     (:name frame)]))

(mf/defc frame-titles
  {::mf/wrap-props false}
  [props]
  (let [objects         (unchecked-get props "objects")
        zoom            (unchecked-get props "zoom")
        modifiers       (unchecked-get props "modifiers")
        selected        (or (unchecked-get props "selected") #{})
        on-frame-enter  (unchecked-get props "on-frame-enter")
        on-frame-leave  (unchecked-get props "on-frame-leave")
        on-frame-select (unchecked-get props "on-frame-select")
        frames    (cp/select-frames objects)]

    [:g.frame-titles
     (for [frame frames]
       [:& frame-title {:frame frame
                        :selected? (contains? selected (:id frame))
                        :zoom zoom
                        :modifiers modifiers
                        :on-frame-enter on-frame-enter
                        :on-frame-leave on-frame-leave
                        :on-frame-select on-frame-select}])]))
