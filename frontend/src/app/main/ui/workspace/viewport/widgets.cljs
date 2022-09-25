; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.widgets
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape-tree :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.viewport.path-actions :refer [path-actions]]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

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
                     :stroke "var(--color-info)"
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
               (not= :text (:type shape)))
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
  [{:keys [data zoom] :as props}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :width (:width data)
      :height (:height data)
      :style {;; Primary with 0.1 opacity
              :fill "rgb(49, 239, 184, 0.1)"

              ;; Primary color
              :stroke "rgb(49, 239, 184)"
              :stroke-width (/ 1 zoom)}}]))


(mf/defc frame-title
  {::mf/wrap [mf/memo]}
  [{:keys [frame selected? zoom show-artboard-names? on-frame-enter on-frame-leave on-frame-select]}]
  (let [on-mouse-down
        (mf/use-callback
         (mf/deps (:id frame) on-frame-select)
         (fn [bevent]
           (let [event  (.-nativeEvent bevent)]
             (when (= 1 (.-which event))
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (on-frame-select event (:id frame))))))

        on-double-click
        (mf/use-callback
         (mf/deps (:id frame))
         #(st/emit! (dw/go-to-layout :layers)
                    (dw/start-rename-shape (:id frame))))

        on-context-menu
        (mf/use-callback
         (mf/deps frame)
         (fn [bevent]
           (let [event    (.-nativeEvent bevent)
                 position (dom/get-client-position event)]
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (st/emit! (dw/show-shape-context-menu {:position position :shape frame})))))

        on-pointer-enter
        (mf/use-callback
         (mf/deps (:id frame) on-frame-enter)
         (fn [_]
           (on-frame-enter (:id frame))))

        on-pointer-leave
        (mf/use-callback
         (mf/deps (:id frame) on-frame-leave)
         (fn [_]
           (on-frame-leave (:id frame))))
        text-pos-x (if (:use-for-thumbnail? frame) 15 0)]

    (when (not (:hidden frame))
      [:g.frame-title {:id (dm/str "frame-title-" (:id frame)) :transform (vwu/title-transform frame zoom)}
       (when (:use-for-thumbnail? frame)
         [:svg {:x 0
                :y -9
                :width 12
                :height 12
                :class "workspace-frame-icon"
                :style {:fill (when selected? "var(--color-primary-dark)")}
                :visibility (if show-artboard-names? "visible" "hidden")}
          [:use {:href "#icon-set-thumbnail"}]])
       [:text {:x text-pos-x
               :y 0
               :width (:width frame)
               :height 20
               :class "workspace-frame-label"
               ;:transform (dm/str frame-transform " " (text-transform label-pos zoom))
               :style {:fill (when selected? "var(--color-primary-dark)")}
               :visibility (if show-artboard-names? "visible" "hidden")
               :on-mouse-down on-mouse-down
               :on-double-click on-double-click
               :on-context-menu on-context-menu
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave}
        (:name frame)]])))

(mf/defc frame-titles
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [objects         (unchecked-get props "objects")
        zoom            (unchecked-get props "zoom")
        selected        (or (unchecked-get props "selected") #{})
        show-artboard-names? (unchecked-get props "show-artboard-names?")
        on-frame-enter  (unchecked-get props "on-frame-enter")
        on-frame-leave  (unchecked-get props "on-frame-leave")
        on-frame-select (unchecked-get props "on-frame-select")
        frames          (ctt/get-frames objects)]

    [:g.frame-titles
     (for [frame frames]
       (when (= (:frame-id frame) uuid/zero)
         [:& frame-title {:key (dm/str "frame-title-" (:id frame))
                          :frame frame
                          :selected? (contains? selected (:id frame))
                          :zoom zoom
                          :show-artboard-names? show-artboard-names?
                          :on-frame-enter on-frame-enter
                          :on-frame-leave on-frame-leave
                          :on-frame-select on-frame-select}]))]))

(mf/defc frame-flow
  [{:keys [flow frame modifiers selected? zoom on-frame-enter on-frame-leave on-frame-select]}]
  (let [{:keys [x y]} (gsh/transform-shape frame)
        flow-pos (gpt/point x (- y (/ 35 zoom)))

        on-mouse-down
        (mf/use-callback
         (mf/deps (:id frame) on-frame-select)
         (fn [bevent]
           (let [event  (.-nativeEvent bevent)]
             (when (= 1 (.-which event))
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (on-frame-select event (:id frame))))))

        on-double-click
        (mf/use-callback
          (mf/deps (:id frame))
          #(st/emit! (dwi/start-rename-flow (:id flow))))

        on-pointer-enter
        (mf/use-callback
         (mf/deps (:id frame) on-frame-enter)
         (fn [_]
           (on-frame-enter (:id frame))))

        on-pointer-leave
        (mf/use-callback
         (mf/deps (:id frame) on-frame-leave)
         (fn [_]
           (on-frame-leave (:id frame))))]

    [:foreignObject {:x 0
                     :y -15
                     :width 100000
                     :height 24
                     :transform (str (when (and selected? modifiers)
                                       (str (:displacement modifiers) " " ))
                                     (vwu/text-transform flow-pos zoom))}
     [:div.flow-badge {:class (dom/classnames :selected selected?)}
      [:div.content {:on-mouse-down on-mouse-down
                     :on-double-click on-double-click
                     :on-pointer-enter on-pointer-enter
                     :on-pointer-leave on-pointer-leave}
       i/play
       [:span (:name flow)]]]]))

(mf/defc frame-flows
  {::mf/wrap-props false}
  [props]
  (let [flows     (unchecked-get props "flows")
        objects   (unchecked-get props "objects")
        zoom      (unchecked-get props "zoom")
        modifiers (unchecked-get props "modifiers")
        selected  (or (unchecked-get props "selected") #{})

        on-frame-enter  (unchecked-get props "on-frame-enter")
        on-frame-leave  (unchecked-get props "on-frame-leave")
        on-frame-select (unchecked-get props "on-frame-select")]
    [:g.frame-flows
     (for [[index flow] (d/enumerate flows)]
       (let [frame (get objects (:starting-frame flow))]
         [:& frame-flow {:key (dm/str (:id frame) "-" index)
                         :flow flow
                         :frame frame
                         :selected? (contains? selected (:id frame))
                         :zoom zoom
                         :modifiers modifiers
                         :on-frame-enter on-frame-enter
                         :on-frame-leave on-frame-leave
                         :on-frame-select on-frame-select}]))]))

