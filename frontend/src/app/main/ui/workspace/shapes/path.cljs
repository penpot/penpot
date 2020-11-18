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
   [app.common.geom.shapes.path :as gsp]
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
        shape (update shape :content gsp/apply-content-modifiers content-modifiers)]

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


(mf/defc path-preview [{:keys [zoom command from]}]
  (when (not= :move-to (:command command))
    [:path {:style {:fill "transparent"
                    :stroke secondary-color
                    :stroke-width (/ 1 zoom)}
            :d (ugp/content->path [{:command :move-to
                                    :params {:x (:x from)
                                             :y (:y from)}}
                                   command])}]))

(mf/defc path-point [{:keys [index position stroke-color fill-color zoom edit-mode selected]}]
  (let [{:keys [x y]} position
        on-click (fn [event]
                   (cond
                     (= edit-mode :move)
                     (do
                       (dom/stop-propagation event)
                       (dom/prevent-default event)
                       (st/emit! (drp/select-node index)))))

        on-mouse-down (fn [event]
                        (cond
                          (= edit-mode :move)
                          (do
                            (dom/stop-propagation event)
                            (dom/prevent-default event)
                            (st/emit! (drp/start-move-path-point index)))))]
    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (/ 3 zoom)
       :style { ;; :cursor cur/resize-alt
               :stroke-width (/ 1 zoom)
               :stroke (or stroke-color black-color)
               :fill (or fill-color white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ 10 zoom)
               :on-click on-click
               :on-mouse-down on-mouse-down
               :style {:fill "transparent"}}]]
    ))

(mf/defc path-handler [{:keys [index point handler zoom selected type edit-mode]}]
  (when (and point handler)
    (let [{:keys [x y]} handler
          on-click (fn [event]
                     (cond
                       (= edit-mode :move)
                       (do
                         (dom/stop-propagation event)
                         (dom/prevent-default event)
                         (drp/select-handler index type))))

          on-mouse-down (fn [event]
                          (cond
                            (= edit-mode :move)
                            (do
                              (dom/stop-propagation event)
                              (dom/prevent-default event)
                              (st/emit! (drp/start-move-handler index type)))))]
      [:g.handler {:class (name type)}
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
         
         :style {;; :cursor cur/resize-alt
                 :stroke-width (/ 1 zoom)
                 :stroke (if selected black-color primary-color)
                 :fill (if selected primary-color white-color)}}]

       [:circle {:cx x
                 :cy y
                 :r (/ 10 zoom)
                 :on-click on-click
                 :on-mouse-down on-mouse-down
                 :style {:fill "transparent"}}]])))

(mf/defc path-editor
  [{:keys [shape zoom]}]

  (let [{:keys [content]} shape
        edit-path-ref (make-edit-path-ref (:id shape))
        {:keys [edit-mode selected drag-handler prev-handler preview content-modifiers]} (mf/deref edit-path-ref)
        selected (or selected #{})
        content (gsp/apply-content-modifiers content content-modifiers)
        points (gsp/content->points content)
        last-command (last content)
        last-p (last points)]

    [:g.path-editor
     (when (and preview (not drag-handler))
       [:g.preview {:style {:pointer-events "none"}}
        [:& path-preview {:command preview
                          :from last-p
                          :zoom zoom}]
        [:& path-point {:position (:params preview)
                        :fill-color secondary-color
                        :zoom zoom}]])

     (for [[index [cmd next]] (d/enumerate (d/with-next content))]
       (let [point (gpt/point (:params cmd))]
         [:g.path-node
          (when (= :curve-to (:command cmd))
            [:& path-handler {:point point
                              :handler (gpt/point (-> cmd :params :c2x) (-> cmd :params :c2y))
                              :zoom zoom
                              :type :prev
                              :index index
                              :selected (selected [index :prev])
                              :edit-mode edit-mode}])

          (when (= :curve-to (:command next))
            [:& path-handler {:point point
                              :handler (gpt/point (-> next :params :c1x) (-> next :params :c1y))
                              :zoom zoom
                              :type :next
                              :index index
                              :selected (selected [index :next])
                              :edit-mode edit-mode}])

          (when (and (= index (dec (count content)))
                     prev-handler (not drag-handler))
            [:& path-handler {:point point
                              :handler prev-handler
                              :zoom zoom
                              :type :prev
                              :index index
                              :selected (selected index)
                              :edit-mode edit-mode}])

          [:& path-point {:position point
                          :stroke-color (when-not (selected index) primary-color)
                          :fill-color (when (selected index) primary-color)
                          :index index
                          :zoom zoom
                          :edit-mode edit-mode}]]))

     (when drag-handler
       [:g.drag-handler
        (when (not= :move-to (:command last-command))
          [:& path-handler {:point last-p
                            :handler (ugp/opposite-handler last-p drag-handler)
                            :zoom zoom
                            :type :drag-opposite
                            :selected false}])
        [:& path-handler {:point last-p
                          :handler drag-handler
                          :zoom zoom
                          :type :drag
                          :selected false}]])]))
