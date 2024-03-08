; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.widgets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
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
                     :stroke (if (dbg/enabled? :pixel-grid) "red" "var(--color-info)")
                     :stroke-opacity (if (dbg/enabled? :pixel-grid) 1 "0.2")
                     :stroke-width (str (/ 1 zoom))}}]]]
   [:rect {:x (:x vbox)
           :y (:y vbox)
           :width (:width vbox)
           :height (:height vbox)
           :fill (str "url(#pixel-grid)")
           :style {:pointer-events "none"}}]])

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
              :fill "var(--color-accent-tertiary-muted)"
              :stroke "var(--color-accent-tertiary)"
              :stroke-width (/ 1 zoom)}}]))


(mf/defc frame-title
  {::mf/wrap [mf/memo
              #(mf/deferred % ts/raf)]}
  [{:keys [frame selected? zoom show-artboard-names? show-id? on-frame-enter on-frame-leave on-frame-select grid-edition?]}]
  (let [workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color (if selected?
                (if (ctn/in-any-component? objects frame)
                  "var(--color-component-highlight)"
                  "var(--color-accent-tertiary)")
                "#8f9da3") ;; TODO: Set this color on the DS

        on-pointer-down
        (mf/use-callback
         (mf/deps (:id frame) on-frame-select workspace-read-only?)
         (fn [bevent]
           (let [event  (.-nativeEvent bevent)]
             (when (= 1 (.-which event))
               (dom/prevent-default bevent)
               (dom/stop-propagation bevent)
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

        main-instance? (ctk/main-instance? frame)

        text-width (* (:width frame) zoom)
        show-icon? (and (or (:use-for-thumbnail frame) grid-edition? main-instance?)
                        (not (<= text-width 15)))
        text-pos-x (if show-icon? 15 0)]

    (when (not (:hidden frame))
      [:g.frame-title {:id (dm/str "frame-title-" (:id frame))
                       :data-edit-grid grid-edition?
                       :transform (vwu/title-transform frame zoom grid-edition?)
                       :pointer-events (when (:blocked frame) "none")}
       (cond
         show-icon?
         [:svg {:x 0
                :y -9
                :width 12
                :height 12
                :class "workspace-frame-icon"
                :style {:stroke color
                        :fill "none"}
                :visibility (if show-artboard-names? "visible" "hidden")}
          (cond
            (:use-for-thumbnail frame)
            [:use {:href "#icon-boards-thumbnail"}]

            grid-edition?
            [:use {:href "#icon-grid"}]

            main-instance?
            [:use {:href "#icon-component"}])])


       [:foreignObject {:x text-pos-x
                        :y -11
                        :width (max 0 (- text-width text-pos-x))
                        :height 20
                        :class (stl/css :workspace-frame-label-wrapper)
                        :style {:fill color}
                        :visibility (if show-artboard-names? "visible" "hidden")}
        [:div {:class (stl/css :workspace-frame-label)
               :style {:color color}
               :on-pointer-down on-pointer-down
               :on-double-click on-double-click
               :on-context-menu on-context-menu
               :on-pointer-enter on-pointer-enter
               :on-pointer-leave on-pointer-leave}
         (if show-id?
           (dm/str (dm/str (:id frame)) " - " (:name frame))
           (:name frame))]]])))

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
        components-v2        (mf/use-ctx ctx/components-v2)
        shapes               (ctt/get-frames objects {:skip-copies? components-v2})
        shapes               (if (dbg/enabled? :shape-titles)
                               (into (set shapes)
                                     (map (d/getf objects))
                                     selected)
                               shapes)
        focus                (unchecked-get props "focus")

        edition              (mf/deref refs/selected-edition)
        grid-edition?        (ctl/grid-layout? objects edition)]

    [:g.frame-titles
     (for [{:keys [id parent-id] :as shape} shapes]
       (when (and
              (not= id uuid/zero)
              (or (dbg/enabled? :shape-titles) (= parent-id uuid/zero))
              (or (empty? focus) (contains? focus id)))
         [:& frame-title {:key (dm/str "frame-title-" id)
                          :frame shape
                          :selected? (contains? selected id)
                          :zoom zoom
                          :show-artboard-names? show-artboard-names?
                          :show-id? (dbg/enabled? :shape-titles)
                          :on-frame-enter on-frame-enter
                          :on-frame-leave on-frame-leave
                          :on-frame-select on-frame-select
                          :grid-edition? (and (= id edition) grid-edition?)}]))]))

(mf/defc frame-flow
  [{:keys [flow frame selected? zoom on-frame-enter on-frame-leave on-frame-select]}]
  (let [{:keys [x y]} frame
        flow-pos (gpt/point x (- y (/ 35 zoom)))

        on-pointer-down
        (mf/use-callback
         (mf/deps (:id frame) on-frame-select)
         (fn [bevent]
           (let [event  (.-nativeEvent bevent)
                 params {:section "interactions"
                         :frame-id (:id frame)}]
             (when (= 1 (.-which event))
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (st/emit! (dw/go-to-viewer params))))))

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
     [:div {:class (stl/css-case :flow-badge true
                                 :selected selected?)}
      [:div {:class (stl/css :content)
             :on-pointer-down on-pointer-down
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

