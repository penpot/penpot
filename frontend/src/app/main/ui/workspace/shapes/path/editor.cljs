;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.main.data.workspace.path :as drp]
   [app.main.store :as st]
   [app.main.ui.cursors :as cur]
   [app.main.ui.workspace.shapes.path.common :as pc]
   [app.util.dom :as dom]
   [app.util.geom.path :as ugp]
   [goog.events :as events]
   [rumext.alpha :as mf]

   [app.util.keyboard :as kbd])
  (:import goog.events.EventType))

(mf/defc path-point [{:keys [position zoom edit-mode hover? selected? preview? start-path? last-p?]}]
  (let [{:keys [x y]} position

        on-enter
        (fn [event]
          (st/emit! (drp/path-pointer-enter position)))
        
        on-leave
        (fn [event]
          (st/emit! (drp/path-pointer-leave position)))

        on-mouse-down
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)

          (let [shift? (kbd/shift? event)]
            (cond
              (= edit-mode :move)
              (st/emit! (drp/start-move-path-point position shift?))

              (and (= edit-mode :draw) start-path?)
              (st/emit! (drp/start-path-from-point position))

              (and (= edit-mode :draw) (not start-path?))
              (st/emit! (drp/close-path-drag-start position)))))]

    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (if (or selected? hover?) (/ 3.5 zoom) (/ 3 zoom))
       :style {:stroke-width (/ 1 zoom)
               :stroke (cond (or selected? hover?) pc/black-color
                             preview? pc/secondary-color
                             :else pc/primary-color)
               :fill (cond selected? pc/primary-color
                           :else pc/white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ 10 zoom)
               :on-mouse-down on-mouse-down
               :on-mouse-enter on-enter
               :on-mouse-leave on-leave
               :style {:pointer-events (when last-p? "none")
                       :cursor (cond
                                 (= edit-mode :draw) cur/pen-node
                                 (= edit-mode :move) cur/pointer-node)
                       :fill "transparent"}}]]))

(mf/defc path-handler [{:keys [index prefix point handler zoom selected? hover? edit-mode]}]
  (when (and point handler)
    (let [{:keys [x y]} handler
          on-enter
          (fn [event]
            (st/emit! (drp/path-handler-enter index prefix)))

          on-leave
          (fn [event]
            (st/emit! (drp/path-handler-leave index prefix)))

          on-click
          (fn [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (cond
              (= edit-mode :move)
              (drp/select-handler index prefix)))

          on-mouse-down
          (fn [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)

            (cond
              (= edit-mode :move)
              (st/emit! (drp/start-move-handler index prefix))))]

      [:g.handler {:pointer-events (when (= edit-mode :draw))}
       [:line
        {:x1 (:x point)
         :y1 (:y point)
         :x2 x
         :y2 y
         :style {:stroke (if hover? pc/black-color pc/gray-color)
                 :stroke-width (/ 1 zoom)}}]
       [:rect
        {:x (- x (/ 3 zoom))
         :y (- y (/ 3 zoom))
         :width (/ 6 zoom)
         :height (/ 6 zoom)
         
         :style {:stroke-width (/ 1 zoom)
                 :stroke (cond (or selected? hover?) pc/black-color
                               :else pc/primary-color)
                 :fill (cond selected? pc/primary-color
                             :else pc/white-color)}}]
       [:circle {:cx x
                 :cy y
                 :r (/ 10 zoom)
                 :on-click on-click
                 :on-mouse-down on-mouse-down
                 :on-mouse-enter on-enter
                 :on-mouse-leave on-leave
                 :style {:cursor (when (= edit-mode :move) cur/pointer-move)
                         :fill "transparent"}}]])))

(mf/defc path-preview [{:keys [zoom command from]}]
  [:g.preview {:style {:pointer-events "none"}}
   (when (not= :move-to (:command command))
     [:path {:style {:fill "transparent"
                     :stroke pc/secondary-color
                     :stroke-width (/ 1 zoom)}
             :d (ugp/content->path [{:command :move-to
                                     :params {:x (:x from)
                                              :y (:y from)}}
                                    command])}])
   [:& path-point {:position (:params command)
                   :preview? true
                   :zoom zoom}]])

(mf/defc path-editor
  [{:keys [shape zoom]}]

  (let [editor-ref (mf/use-ref nil)
        edit-path-ref (pc/make-edit-path-ref (:id shape))
        {:keys [edit-mode
                drag-handler
                prev-handler
                preview
                content-modifiers
                last-point
                selected-handlers
                selected-points
                hover-handlers
                hover-points]
         :as edit-path} (mf/deref edit-path-ref)

        {:keys [content]} shape
        content (ugp/apply-content-modifiers content content-modifiers)
        points (->> content ugp/content->points (into #{}))
        last-command (last content)
        last-p (->> content last ugp/command->point)
        handlers (ugp/content->handlers content)

        handle-double-click-outside
        (fn [event]
          (when (= edit-mode :move)
            (st/emit! :interrupt)))]

    (mf/use-layout-effect
     (mf/deps edit-mode)
     (fn []
       (let [keys [(events/listen (dom/get-root) EventType.DBLCLICK handle-double-click-outside)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    [:g.path-editor {:ref editor-ref}
     (when (and preview (not drag-handler))
       [:& path-preview {:command preview
                         :from last-p
                         :zoom zoom}])

     (for [position points]
       (let [point-selected? (contains? selected-points position)
             point-hover? (contains? hover-points position)
             last-p? (= last-point position)
             start-p? (not (some? last-point))]
         [:g.path-node
          [:g.point-handlers {:pointer-events (when (= edit-mode :draw) "none")}
           (for [[index prefix] (get handlers position)]
             (let [command (get content index)
                   x (get-in command [:params (d/prefix-keyword prefix :x)])
                   y (get-in command [:params (d/prefix-keyword prefix :y)])
                   handler-position (gpt/point x y)
                   handler-selected? (contains? selected-handlers [index prefix])
                   handler-hover? (contains? hover-handlers [index prefix])]
               (when (not= position handler-position)
                 [:& path-handler {:point position
                                   :handler handler-position
                                   :index index
                                   :prefix prefix
                                   :zoom zoom
                                   :selected? handler-selected?
                                   :hover? handler-hover?
                                   :edit-mode edit-mode}])))]
          [:& path-point {:position position
                          :zoom zoom
                          :edit-mode edit-mode
                          :selected? point-selected?
                          :hover? point-hover?
                          :last-p? last-p?
                          :start-path? start-p?}]]))

     (when prev-handler
       [:g.prev-handler {:pointer-events "none"}
        [:& path-handler {:point last-p
                          :handler prev-handler
                          :zoom zoom}]])

     (when drag-handler
       [:g.drag-handler {:pointer-events "none"}
        [:& path-handler {:point last-p
                          :handler drag-handler
                          :zoom zoom}]])]))

