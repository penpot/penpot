;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.path
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.util.data :as d]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [app.main.refs :as refs]
   [app.main.streams :as ms]
   [app.main.constants :as c]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dr]
   [app.main.data.workspace.drawing.path :as drp]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :as common]
   [app.util.geom.path :as ugp]
   [app.common.geom.point :as gpt]
   [app.main.ui.cursors :as cur]
   [app.main.ui.icons :as i]))

(def primary-color "#1FDEA7")
(def secondary-color "#DB00FF")
(def black-color "#000000")
(def white-color "#FFFFFF")
(def gray-color "#B1B2B5")

(def current-edit-path-ref
  (let [selfn (fn [local]
                (let [id (:edition local)]
                  (get-in local [:edit-path id])))]
    (l/derived selfn refs/workspace-local)))

(defn make-edit-path-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id])]
     #(l/derived selfn refs/workspace-local))))

(defn make-content-modifiers-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id :content-modifiers])]
     #(l/derived selfn refs/workspace-local))))

(mf/defc path-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        hover? (or (mf/deref refs/current-hover) #{})

        on-mouse-down   (mf/use-callback
                         (mf/deps shape)
                         #(common/on-mouse-down % shape))
        on-context-menu (mf/use-callback
                         (mf/deps shape)
                         #(common/on-context-menu % shape))

        on-double-click (mf/use-callback
                         (mf/deps shape)
                         (fn [event]
                           (when (not (::dr/initialized? shape))
                             (do
                               (dom/stop-propagation event)
                               (dom/prevent-default event)
                               (st/emit! (dw/start-edition-mode (:id shape))
                                         (dw/start-path-edit (:id shape)))))))
        content-modifiers-ref (make-content-modifiers-ref (:id shape))
        content-modifiers (mf/deref content-modifiers-ref)
        editing-id (mf/deref refs/selected-edition)
        editing? (= editing-id (:id shape))
        shape (update shape :content ugp/apply-content-modifiers content-modifiers)]

    [:> shape-container {:shape shape
                         :pointer-events (when editing? "none")
                         :on-double-click on-double-click
                         :on-mouse-down on-mouse-down
                         :on-context-menu on-context-menu}
     [:& path/path-shape {:shape shape
                          :background? true}]]))

(mf/defc path-actions [{:keys [shape]}]
  (let [id (mf/deref refs/selected-edition)
        {:keys [edit-mode selected snap-toggled] :as all} (mf/deref current-edit-path-ref)]
    [:div.path-actions
     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class (when (= edit-mode :draw) "is-toggled")
                                    :on-click #(st/emit! (drp/change-edit-mode :draw))} i/pen]
      [:div.viewport-actions-entry {:class (when (= edit-mode :move) "is-toggled")
                                    :on-click #(st/emit! (drp/change-edit-mode :move))} i/pointer-inner]]
     
     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-add]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-remove]]

     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-merge]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-join]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-separate]]

     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-corner]
      [:div.viewport-actions-entry {:class "is-disabled"} i/nodes-curve]]

     [:div.viewport-actions-group
      [:div.viewport-actions-entry {:class (when snap-toggled "is-toggled")} i/nodes-snap]]]))


(mf/defc path-point [{:keys [position zoom edit-mode hover? selected? preview? start-path?]}]
  (let [{:keys [x y]} position

        on-enter
        (fn [event]
          (st/emit! (drp/path-pointer-enter position)))
        
        on-leave
        (fn [event]
          (st/emit! (drp/path-pointer-leave position)))

        on-click
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)

          (cond
            (and (= edit-mode :move) (not selected?))
            (st/emit! (drp/select-node position))

            (and (= edit-mode :move) selected?)
            (st/emit! (drp/deselect-node position))))

        on-mouse-down
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)

          (cond
            (= edit-mode :move)
            (st/emit! (drp/start-move-path-point position))

            (and (= edit-mode :draw) start-path?)
            (st/emit! (drp/start-path-from-point position))

            (and (= edit-mode :draw) (not start-path?))
            (st/emit! (drp/close-path-drag-start position))))]
    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (/ 3 zoom)
       :style {:cursor (when (= edit-mode :draw) cur/pen-node)
               :stroke-width (/ 1 zoom)
               :stroke (cond (or selected? hover?) black-color
                             preview? secondary-color
                             :else primary-color)
               :fill (cond selected? primary-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ 10 zoom)
               :on-click on-click
               :on-mouse-down on-mouse-down
               :style {:fill "transparent"}}]]))

(mf/defc path-handler [{:keys [index prefix point handler zoom selected? hover? edit-mode]}]
  (when (and point handler)
    (let [{:keys [x y]} handler
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
         :style {:stroke gray-color
                 :stroke-width (/ 1 zoom)}}]
       [:rect
        {:x (- x (/ 3 zoom))
         :y (- y (/ 3 zoom))
         :width (/ 6 zoom)
         :height (/ 6 zoom)
         
         :style {:cursor cur/pointer-move
                 :stroke-width (/ 1 zoom)
                 :stroke (cond (or selected? hover?) black-color
                               :else primary-color)
                 :fill (cond selected? primary-color
                             :else white-color)}}]
       [:circle {:cx x
                 :cy y
                 :r (/ 10 zoom)
                 :on-click on-click
                 :on-mouse-down on-mouse-down
                 :style {:fill "transparent"}}]])))

(mf/defc path-preview [{:keys [zoom command from]}]
  [:g.preview {:style {:pointer-events "none"}}
   (when (not= :move-to (:command command))
     [:path {:style {:fill "transparent"
                     :stroke secondary-color
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

  (let [edit-path-ref (make-edit-path-ref (:id shape))
        {:keys [edit-mode selected drag-handler prev-handler preview content-modifiers last-point]} (mf/deref edit-path-ref)
        {:keys [content]} shape
        selected (or selected #{})
        content (ugp/apply-content-modifiers content content-modifiers)
        points (->> content ugp/content->points (into #{}))
        last-command (last content)
        last-p (->> content last ugp/command->point)
        handlers (ugp/content->handlers content)]

    [:g.path-editor
     (when (and preview (not drag-handler))
       [:& path-preview {:command preview
                         :from last-p
                         :zoom zoom}])

     (for [position points]
       [:g.path-node
        [:& path-point {:position position
                        :selected? false
                        :zoom zoom
                        :edit-mode edit-mode
                        :start-path? (nil? last-point)}]

        [:g.point-handlers {:pointer-events (when (= edit-mode :draw) "none")}
         (for [[index prefix] (get handlers position)]
           (let [command (get content index)
                 x (get-in command [:params (d/prefix-keyword prefix :x)])
                 y (get-in command [:params (d/prefix-keyword prefix :y)])
                 handler-position (gpt/point x y)]
             [:& path-handler {:point position
                               :handler handler-position
                               :index index
                               :prefix prefix
                               :zoom zoom
                               :selected? false
                               :hover? false
                               :preview? false
                               :edit-mode edit-mode}]))]])

     (when prev-handler
       [:g.prev-handler {:pointer-events "none"}
        [:& path-handler {:point last-p
                          :handler prev-handler
                          :zoom zoom
                          :selected false}]])

     (when drag-handler
       [:g.drag-handler {:pointer-events "none"}
        (when (not= :move-to (:command last-command))
          [:& path-handler {:point last-p
                            :handler (ugp/opposite-handler last-p drag-handler)
                            :zoom zoom
                            :selected false}])
        [:& path-handler {:point last-p
                          :handler drag-handler
                          :zoom zoom
                          :selected false}]])]))
