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
   [app.main.data.common :as dcm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc pixel-grid*
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
                     :stroke (if (dbg/enabled? :pixel-grid) "red" "var(--status-color-info-500)")
                     :stroke-opacity (if (dbg/enabled? :pixel-grid) 1 "0.2")
                     :stroke-width (str (/ 1 zoom))}}]]]
   [:rect {:x (:x vbox)
           :y (:y vbox)
           :width (:width vbox)
           :height (:height vbox)
           :fill (str "url(#pixel-grid)")
           :style {:pointer-events "none"}}]])

(mf/defc cursor-tooltip*
  [{:keys [zoom tooltip]}]
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom)))
        pos-x (- (:x coords) 100)
        pos-y (+ (:y coords) 30)]
    [:g {:transform (str "translate(" pos-x "," pos-y ")")}
     [:foreignObject {:width 200 :height 100 :style {:text-align "center"}}
      [:span tooltip]]]))

(mf/defc selection-rect*
  {:wrap [mf/memo]}
  [{:keys [data zoom]}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :data-testid "workspace-selection-rect"
      :width (:width data)
      :height (:height data)
      :style {;; Primary with 0.1 opacity
              :fill "var(--color-accent-tertiary-muted)"
              :stroke "var(--color-accent-tertiary)"
              :stroke-width (/ 1 zoom)}}]))


(mf/defc frame-title
  {::mf/wrap [mf/memo
              #(mf/deferred % ts/raf)]
   ::mf/forward-ref true}
  [{:keys [frame zoom is-selected is-show-artboard-names is-show-id is-grid-edition
           on-frame-enter on-frame-leave on-frame-select]} external-ref]
  (let [workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color (if is-selected
                (if (or (ctn/in-any-component? objects frame) (ctk/is-variant-container? frame))
                  "var(--assets-component-hightlight)"
                  "var(--color-accent-tertiary)")
                "#8f9da3") ;; TODO: Set this color on the DS

        blocked? (:blocked frame)

        on-pointer-down
        (mf/use-fn
         (mf/deps (:id frame) on-frame-select workspace-read-only? blocked?)
         (fn [event]
           (when (and (dom/left-mouse? event) (not blocked?))
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (on-frame-select event (:id frame)))))

        on-context-menu
        (mf/use-fn
         (mf/deps frame workspace-read-only?)
         (fn [bevent]
           (let [event    (dom/event->native-event bevent)
                 position (dom/get-client-position event)]
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (when-not workspace-read-only?
               (st/emit! (dw/show-shape-context-menu {:position position :shape frame}))))))

        on-pointer-enter
        (mf/use-fn
         (mf/deps (:id frame) on-frame-enter)
         (fn [_]
           (on-frame-enter (:id frame))))

        on-pointer-leave
        (mf/use-fn
         (mf/deps (:id frame) on-frame-leave)
         (fn [_]
           (on-frame-leave (:id frame))))

        main-instance? (ctk/main-instance? frame)
        is-variant?    (:is-variant-container frame)

        text-width (* (:width frame) zoom)
        show-icon? (and (or (:use-for-thumbnail frame) is-grid-edition main-instance? is-variant?)
                        (not (<= text-width 15)))
        text-pos-x (if show-icon? 15 0)

        edition*         (mf/use-state false)
        edition?         (deref edition*)

        local-ref        (mf/use-ref)
        ref              (d/nilv external-ref local-ref)

        frame-id  (:id frame)

        start-edit
        (mf/use-fn
         (mf/deps frame-id edition? blocked? workspace-read-only?)
         (fn []
           (when (and (not blocked?)
                      (not workspace-read-only?))
             (if (not edition?)
               (reset! edition* true)
               (st/emit! (dw/start-rename-shape frame-id))))))

        accept-edit
        (mf/use-fn
         (mf/deps frame-id)
         (fn []
           (let [name-input     (mf/ref-val ref)
                 name           (str/trim (dom/get-value name-input))]
             (reset! edition* false)
             (st/emit! (dw/end-rename-shape frame-id name)))))

        cancel-edit
        (mf/use-fn
         (mf/deps frame-id)
         (fn []
           (reset! edition* false)
           (st/emit! (dw/end-rename-shape frame-id nil))))

        on-key-down
        (mf/use-fn
         (mf/deps accept-edit cancel-edit)
         (fn [event]
           (when (kbd/enter? event) (accept-edit))
           (when (kbd/esc? event) (cancel-edit))))]


    (when (not (:hidden frame))
      [:g.frame-title {:id (dm/str "frame-title-" (:id frame))
                       :data-edit-grid is-grid-edition
                       :transform (vwu/title-transform frame zoom is-grid-edition)
                       :pointer-events (when (:blocked frame) "none")}
       (when show-icon?
         [:svg {:x 0
                :y -9
                :width 12
                :height 12
                :class "workspace-frame-icon"
                :style {:stroke color
                        :fill "none"}
                :visibility (if is-show-artboard-names "visible" "hidden")}
          (cond
            (:use-for-thumbnail frame) [:use {:href "#icon-boards-thumbnail"}]
            is-grid-edition            [:use {:href "#icon-grid"}]
            main-instance?             [:use {:href "#icon-component"}]
            is-variant?                [:use {:href "#icon-component"}])])

       (if ^boolean edition?
           ;; Case when edition? is true
         [:foreignObject {:x text-pos-x
                          :y -15
                          :width (max 0 (- text-width text-pos-x))
                          :height 22
                          :class (stl/css :frame-title-wrapper)
                          :style {:fill color}
                          :visibility (if is-show-artboard-names "visible" "hidden")}
          [:input {:type "text"
                   :class (stl/css :frame-title-label
                                   :frame-title-input)
                   :style {:color color}
                   :auto-focus true
                   :on-key-down on-key-down
                   :ref ref
                   :default-value (:name frame)
                   :on-blur accept-edit}]]
           ;; Case when edition? is false
         [:foreignObject {:x text-pos-x
                          :y -11
                          :width (max 0 (- text-width text-pos-x))
                          :height 20
                          :class (stl/css :frame-title-wrapper)
                          :style {:fill color}
                          :visibility (if is-show-artboard-names "visible" "hidden")}
          [:div {:class (stl/css :frame-title-label)
                 :style {:color color}
                 :ref ref
                 :on-pointer-down on-pointer-down
                 :on-double-click start-edit
                 :on-context-menu on-context-menu
                 :on-pointer-enter on-pointer-enter
                 :on-pointer-leave on-pointer-leave}
           (if is-show-id
             (dm/str (:id frame) " - " (:name frame))
             (:name frame))]])])))

(mf/defc frame-titles*
  {::mf/wrap [mf/memo]}
  [{:keys [objects zoom selected focus is-show-artboard-names
           on-frame-enter on-frame-leave on-frame-select]}]
  (let [selected       (or selected #{})
        shapes         (ctt/get-frames objects {:skip-copies? true})
        shapes         (if (dbg/enabled? :shape-titles)
                         (into (set shapes)
                               (map (d/getf objects))
                               selected)
                         shapes)

        edition        (mf/deref refs/selected-edition)
        grid-edition?  (ctl/grid-layout? objects edition)]

    [:g.frame-titles
     (for [{:keys [id parent-id] :as shape} shapes]
       (when (and
              (not= id uuid/zero)
              (or (dbg/enabled? :shape-titles) (= parent-id uuid/zero))
              (or (empty? focus) (contains? focus id)))
         [:& frame-title {:key (dm/str "frame-title-" id)
                          :frame shape
                          :zoom zoom
                          :is-selected (contains? selected id)
                          :is-show-artboard-names is-show-artboard-names
                          :is-show-id (dbg/enabled? :shape-titles)
                          :is-grid-edition (and (= id edition) grid-edition?)
                          :on-frame-enter on-frame-enter
                          :on-frame-leave on-frame-leave
                          :on-frame-select on-frame-select}]))]))

(mf/defc frame-flow*
  [{:keys [flow frame is-selected zoom on-frame-enter on-frame-leave on-frame-select]}]
  (let [x         (dm/get-prop frame :x)
        y         (dm/get-prop frame :y)
        pos       (gpt/point x (- y (/ 35 zoom)))

        frame-id  (:id frame)
        flow-id   (:id flow)
        flow-name (:name flow)

        on-pointer-down
        (mf/use-fn
         (mf/deps frame-id on-frame-select)
         (fn [event]
           (let [params {:section "interactions"
                         :frame-id frame-id}]
             (when (dom/left-mouse? event)
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (st/emit! (dcm/go-to-viewer params))))))

        on-double-click
        (mf/use-fn
         (mf/deps flow-id)
         #(st/emit! (dwi/start-rename-flow flow-id)))

        on-pointer-enter
        (mf/use-fn
         (mf/deps frame-id on-frame-enter)
         (fn [_]
           (when (fn? on-frame-enter)
             (on-frame-enter frame-id))))

        on-pointer-leave
        (mf/use-fn
         (mf/deps frame-id on-frame-leave)
         (fn [_]
           (when (fn? on-frame-leave)
             (on-frame-leave frame-id))))]

    [:foreignObject {:x 0
                     :y -15
                     :width 100000
                     :height 24
                     :transform (vwu/text-transform pos zoom)}
     [:div {:class (stl/css :frame-flow-badge-wrapper)}
      [:div {:class (stl/css-case :frame-flow-badge-content true
                                  :selected is-selected)
             :on-pointer-down on-pointer-down
             :on-double-click on-double-click
             :on-pointer-enter on-pointer-enter
             :on-pointer-leave on-pointer-leave}
       [:> icon* {:icon-id i/play
                  :size "s"}]
       [:span flow-name]]]]))

(mf/defc frame-flows*
  [{:keys [flows objects zoom selected on-frame-enter on-frame-leave on-frame-select]}]
  [:g.frame-flows
   (for [[flow-id flow] flows]
     (let [frame    (get objects (:starting-frame flow))
           frame-id (dm/get-prop frame :id)]
       [:> frame-flow* {:key (dm/str frame-id "-" flow-id)
                        :flow flow
                        :frame frame
                        :is-selected (contains? selected frame-id)
                        :zoom zoom
                        :on-frame-enter on-frame-enter
                        :on-frame-leave on-frame-leave
                        :on-frame-select on-frame-select}]))])

(mf/defc button-add*
  [{:keys [shape zoom on-click]}]
  (let [{:keys [x2 y2 height]} (:selrect shape)

        center-x (+ x2 (/ 22 zoom))
        center-y (- y2 (/ height 2))

        rect-x   (- center-x (/ 16 zoom))
        rect-y   (- center-y (/ 16 zoom))
        rect-sz  (/ 32 zoom)
        rect-r   (/ 8 zoom)

        icon-x   (- center-x (/ 8 zoom))
        icon-y   (- center-y (/ 8 zoom))
        icon-sz  (/ 16 zoom)

        handle-click
        (mf/use-fn
         (mf/deps on-click)
         #(when (fn? on-click)
            (on-click)))]

    [:g {:class (stl/css :button-add-wrapper)
         :on-click handle-click}
     [:rect {:x rect-x
             :y rect-y
             :width rect-sz
             :height rect-sz
             :rx rect-r
             :ry rect-r}]
     [:use {:class (stl/css :button-add-icon)
            :x icon-x
            :y icon-y
            :width icon-sz
            :height icon-sz
            :href "#icon-add"}]]))
