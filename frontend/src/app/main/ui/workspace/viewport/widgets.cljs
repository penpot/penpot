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
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.grid-layout.editor :as dwge]
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
   [app.util.i18n :as i18n :refer [tr]]
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
        shape       (or drawing-obj (-> selected first))

        single? (= (count selected) 1)
        editing? (= (:id shape) edition)
        draw-path? (and (some? drawing-obj)
                        (cph/path-shape? drawing-obj)
                        (not= :curve (:tool drawing)))

        path-edition? (or (and single? editing?
                               (and (not (cph/text-shape? shape))
                                    (not (cph/frame-shape? shape))))
                          draw-path?)

        grid-edition? (and single? editing? (ctl/grid-layout? shape))]

    (cond
      path-edition?
      [:div.viewport-actions
       [:& path-actions {:shape shape}]]

      grid-edition?
      [:div.viewport-actions
       [:div.grid-actions
        [:div.grid-edit-title
         (tr "workspace.layout_grid.editor.title")  " " [:span.grid-edit-board-name (:name shape)]]
        [:button.btn-secondary {:on-click #(st/emit! (dwge/locate-board (:id shape)))} "Locate"]
        [:button.btn-primary {:on-click #(st/emit! dw/clear-edition-mode)} "Done"]
        [:button.btn-icon-basic {:on-click #(st/emit! dw/clear-edition-mode)} i/close]]])))

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
  [{:keys [frame selected? zoom show-artboard-names? show-id? on-frame-enter on-frame-leave on-frame-select grid-edition?]}]
  (let [workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color (when selected?
                (if (ctn/in-any-component? objects frame)
                  "var(--color-component-highlight)"
                  "var(--color-primary-dark)"))

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
        text-pos-x (if (or (:use-for-thumbnail? frame) grid-edition? main-instance?) 15 0)]

    (when (not (:hidden frame))
      [:g.frame-title {:id (dm/str "frame-title-" (:id frame))
                       :data-edit-grid grid-edition?
                       :transform (vwu/title-transform frame zoom grid-edition?)
                       :pointer-events (when (:blocked frame) "none")}
       (cond
         (or (:use-for-thumbnail? frame) grid-edition? main-instance?)
         [:svg {:x 0
                :y -9
                :width 12
                :height 12
                :class "workspace-frame-icon"
                :style {:fill color}
                :visibility (if show-artboard-names? "visible" "hidden")}
          (cond
            (:use-for-thumbnail? frame)
            [:use {:href "#icon-set-thumbnail"}]

            grid-edition?
            [:use {:href "#icon-grid-layout-mode"}]

            main-instance?
            [:use {:href "#icon-component"}])])

       [:text {:x text-pos-x
               :y 0
               :width (:width frame)
               :height 20
               :class "workspace-frame-label"
               :style {:fill color}
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
        components-v2        (mf/use-ctx ctx/components-v2)
        shapes               (ctt/get-frames objects {:skip-copies? components-v2})
        shapes               (if (debug? :shape-titles)
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

