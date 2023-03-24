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
   [app.common.pages.helpers :as cph]
   [app.common.types.shape-tree :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.viewport.path-actions :refer [path-actions]]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [debug :refer [debug?]]
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
                     :stroke (if (debug? :pixel-grid) "red" "var(--color-info)")
                     :stroke-opacity (if (debug? :pixel-grid) 1 "0.2")
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
  (let [edition     (mf/deref refs/selected-edition)
        selected    (mf/deref refs/selected-objects)
        drawing     (mf/deref refs/workspace-drawing)
        drawing-obj (:object drawing)
        shape       (or drawing-obj (-> selected first))]
    (when (or (and (= (count selected) 1)
                   (= (:id shape) edition)
                   (and (not (cph/text-shape? shape))
                        (not (cph/frame-shape? shape))))
              (and (some? drawing-obj)
                   (cph/path-shape? drawing-obj)
                   (not= :curve (:tool drawing))))
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
  {::mf/wrap [mf/memo
              #(mf/deferred % ts/raf)]}
  [{:keys [frame selected? zoom show-artboard-names? show-id? on-frame-enter on-frame-leave on-frame-select]}]
  (let [workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        on-pointer-down
        (mf/use-callback
         (mf/deps (:id frame) on-frame-select workspace-read-only?)
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
         (mf/deps frame workspace-read-only?)
         (fn [bevent]
           (let [event    (.-nativeEvent bevent)
                 position (dom/get-client-position event)]
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (when-not workspace-read-only?
               (st/emit! (dw/show-shape-context-menu {:position position :shape frame}))))))

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
               :style {:fill (when selected? "var(--color-primary-dark)")}
               :visibility (if show-artboard-names? "visible" "hidden")
               :on-pointer-down on-pointer-down
               :on-double-click on-double-click
               :on-context-menu on-context-menu
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave}
        (if show-id?
          (dm/str (dm/str (:id frame)) " - " (:name frame))
          (:name frame))]])))

(mf/defc frame-titles
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [objects              (unchecked-get props "objects")
        zoom                 (unchecked-get props "zoom")
        selected             (or (unchecked-get props "selected") #{})
        show-artboard-names? (unchecked-get props "show-artboard-names?")
        on-frame-enter       (unchecked-get props "on-frame-enter")
        on-frame-leave       (unchecked-get props "on-frame-leave")
        on-frame-select      (unchecked-get props "on-frame-select")
        shapes               (ctt/get-frames objects)
        shapes               (if (debug? :shape-titles)
                               (into (set shapes)
                                     (map (d/getf objects))
                                     selected)
                               shapes)
        focus                (unchecked-get props "focus")]

    [:g.frame-titles
     (for [{:keys [id parent-id] :as shape} shapes]
       (when (and
              (not= id uuid/zero)
              (or (debug? :shape-titles) (= parent-id uuid/zero))
              (or (empty? focus) (contains? focus id)))
         [:& frame-title {:key (dm/str "frame-title-" id)
                          :frame shape
                          :selected? (contains? selected id)
                          :zoom zoom
                          :show-artboard-names? show-artboard-names?
                          :show-id? (debug? :shape-titles)
                          :on-frame-enter on-frame-enter
                          :on-frame-leave on-frame-leave
                          :on-frame-select on-frame-select}]))]))

(mf/defc frame-flow
  [{:keys [flow frame selected? zoom on-frame-enter on-frame-leave on-frame-select]}]
  (let [{:keys [x y]} frame
        flow-pos (gpt/point x (- y (/ 35 zoom)))

        on-pointer-down
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
                     :transform (vwu/text-transform flow-pos zoom)}
     [:div.flow-badge {:class (dom/classnames :selected selected?)}
      [:div.content {:on-pointer-down on-pointer-down
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
                         :on-frame-enter on-frame-enter
                         :on-frame-leave on-frame-leave
                         :on-frame-select on-frame-select}]))]))

