;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gsp]
   [app.common.path.commands :as upc]
   [app.common.path.shapes-to-path :as ups]
   [app.main.data.workspace.path :as drp]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.shapes.path.common :as pc]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.path.format :as upf]
   [clojure.set :refer [map-invert]]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(def point-radius 5)
(def point-radius-selected 4)
(def point-radius-active-area 15)
(def point-radius-stroke-width 1)

(def handler-side 6)
(def handler-stroke-width 1)

(def path-preview-dasharray 4)
(def path-snap-stroke-width 1)

(mf/defc path-point [{:keys [position zoom edit-mode hover? selected? preview? start-path? last-p? new-point? curve?]}]
  (let [{:keys [x y]} position

        on-enter
        (mf/use-callback
         (fn [_]
           (st/emit! (drp/path-pointer-enter position))))

        on-leave
        (mf/use-callback
         (fn [_]
           (st/emit! (drp/path-pointer-leave position))))

        on-mouse-down
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)

          (when (and new-point? (some? (meta position)))
            (st/emit! (drp/create-node-at-position (meta position))))

          (let [shift? (kbd/shift? event)
                mod?   (kbd/mod? event)]
            (cond
              last-p?
              (st/emit! (drp/reset-last-handler))

              (and (= edit-mode :move) mod? (not curve?))
              (st/emit! (drp/make-curve position))

              (and (= edit-mode :move) mod? curve?)
              (st/emit! (drp/make-corner position))

              (= edit-mode :move)
              ;; If we're dragging a selected item we don't change the selection
              (st/emit! (drp/start-move-path-point position shift?))

              (and (= edit-mode :draw) start-path?)
              (st/emit! (drp/start-path-from-point position))

              (and (= edit-mode :draw) (not start-path?))
              (st/emit! (drp/close-path-drag-start position)))))]

    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (if (or selected? hover?) (/ point-radius zoom) (/ point-radius-selected zoom))
       :style {:stroke-width (/ point-radius-stroke-width zoom)
               :stroke (cond (or selected? hover?) pc/black-color
                             preview? pc/secondary-color
                             :else pc/primary-color)
               :fill (cond selected? pc/primary-color
                           :else pc/white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-mouse-down on-mouse-down
               :on-mouse-enter on-enter
               :on-mouse-leave on-leave
               :pointer-events (when-not preview? "visible")
               :style {:cursor (cond
                                 (= edit-mode :draw) cur/pen-node
                                 (= edit-mode :move) cur/pointer-node)
                       :stroke-width 0
                       :fill "none"}}]]))

(mf/defc path-handler [{:keys [index prefix point handler zoom selected? hover? edit-mode snap-angle?]}]
  (when (and point handler)
    (let [{:keys [x y]} handler
          on-enter
          (fn [_]
            (st/emit! (drp/path-handler-enter index prefix)))

          on-leave
          (fn [_]
            (st/emit! (drp/path-handler-leave index prefix)))

          on-mouse-down
          (fn [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)

            (cond
              (= edit-mode :move)
              (st/emit! (drp/start-move-handler index prefix))))]

      [:g.handler {:pointer-events (if (= edit-mode :draw) "none" "visible")}
       [:line
        {:x1 (:x point)
         :y1 (:y point)
         :x2 x
         :y2 y
         :style {:stroke (if hover? pc/black-color pc/gray-color)
                 :stroke-width (/ point-radius-stroke-width zoom)}}]

       (when snap-angle?
         [:line
          {:x1 (:x point)
           :y1 (:y point)
           :x2 x
           :y2 y
           :style {:stroke pc/secondary-color
                   :stroke-width (/ point-radius-stroke-width zoom)}}])

       [:rect
        {:x (- x (/ handler-side 2 zoom))
         :y (- y (/ handler-side 2 zoom))
         :width (/ handler-side zoom)
         :height (/ handler-side zoom)

         :style {:stroke-width (/ handler-stroke-width zoom)
                 :stroke (cond (or selected? hover?) pc/black-color
                               :else pc/primary-color)
                 :fill (cond selected? pc/primary-color
                             :else pc/white-color)}}]
       [:circle {:cx x
                 :cy y
                 :r (/ point-radius-active-area zoom)
                 :on-mouse-down on-mouse-down
                 :on-mouse-enter on-enter
                 :on-mouse-leave on-leave
                 :style {:cursor (when (= edit-mode :move) cur/pointer-move)
                         :fill "none"
                         :stroke-width 0}}]])))

(mf/defc path-preview [{:keys [zoom command from]}]
  [:g.preview {:style {:pointer-events "none"}}
   (when (not= :move-to (:command command))
     [:path {:style {:fill "none"
                     :stroke pc/black-color
                     :stroke-width (/ handler-stroke-width zoom)
                     :stroke-dasharray (/ path-preview-dasharray zoom)}
             :d (upf/format-path [{:command :move-to
                                   :params {:x (:x from)
                                            :y (:y from)}}
                                  command])}])
   [:& path-point {:position (:params command)
                   :preview? true
                   :zoom zoom}]])

(mf/defc path-snap [{:keys [selected points zoom]}]
  (let [ranges       (mf/use-memo (mf/deps selected points) #(snap/create-ranges points selected))
        snap-matches (snap/get-snap-delta-match selected ranges (/ 1 zoom))
        matches      (concat (second (:x snap-matches)) (second (:y snap-matches)))]

    [:g.snap-paths
     (for [[from to] matches]
       [:line {:x1 (:x from)
               :y1 (:y from)
               :x2 (:x to)
               :y2 (:y to)
               :style {:stroke pc/secondary-color
                       :stroke-width (/ path-snap-stroke-width zoom)}}])]))

(defn matching-handler? [content node handlers]
  (when (= 2 (count handlers))
    (let [[[i1 p1] [i2 p2]] handlers
          p1 (upc/handler->point content i1 p1)
          p2 (upc/handler->point content i2 p2)

          v1 (gpt/to-vec node p1)
          v2 (gpt/to-vec node p2)

          angle (gpt/angle-with-other v1 v2)]
      (<= (- 180 angle) 0.1))))

(mf/defc path-editor
  [{:keys [shape zoom]}]

  (let [editor-ref (mf/use-ref nil)
        edit-path-ref (pc/make-edit-path-ref (:id shape))
        hover-point (mf/use-state nil)

        {:keys [edit-mode
                drag-handler
                prev-handler
                preview
                content-modifiers
                last-point
                selected-points
                moving-nodes
                moving-handler
                hover-handlers
                hover-points
                snap-toggled]
         :as edit-path} (mf/deref edit-path-ref)

        selected-points (or selected-points #{})

        shape (cond-> shape
                (not= :path (:type shape))
                (ups/convert-to-path {})

                :always
                hooks/use-equal-memo)

        base-content (:content shape)
        base-points (mf/use-memo (mf/deps base-content) #(->> base-content gsp/content->points))

        content (upc/apply-content-modifiers base-content content-modifiers)
        content-points (mf/use-memo (mf/deps content) #(->> content gsp/content->points))

        point->base (->> (map hash-map content-points base-points) (reduce merge))
        base->point (map-invert point->base)

        points (into #{} content-points)

        last-p (->> content last upc/command->point)
        handlers (upc/content->handlers content)

        start-p? (not (some? last-point))

        [snap-selected snap-points]
        (cond
          (some? drag-handler) [#{drag-handler} points]
          (some? preview) [#{(upc/command->point preview)} points]
          (some? moving-handler) [#{moving-handler} points]
          :else
          [(->> selected-points (map base->point) (into #{}))
           (->> points (remove selected-points) (into #{}))])

        show-snap? (and snap-toggled
                        (or (some? drag-handler)
                            (some? preview)
                            (some? moving-handler)
                            moving-nodes))

        handle-double-click-outside
        (fn [_]
          (when (= edit-mode :move)
            (st/emit! :interrupt)))]

    (mf/use-layout-effect
     (mf/deps edit-mode)
     (fn []
       (let [keys [(events/listen (dom/get-root) EventType.DBLCLICK handle-double-click-outside)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    (hooks/use-stream
     ms/mouse-position
     (mf/deps shape zoom)
     (fn [position]
       (when-let [point (gsp/path-closest-point shape position)]
         (reset! hover-point (when (< (gpt/distance position point) (/ 10 zoom)) point)))))

    [:g.path-editor {:ref editor-ref}
     [:path {:d (upf/format-path content)
             :style {:fill "none"
                     :stroke pc/primary-color
                     :strokeWidth (/ 1 zoom)}}]
     (when (and preview (not drag-handler))
       [:& path-preview {:command preview
                         :from last-p
                         :zoom zoom}])

     (when drag-handler
       [:g.drag-handler {:pointer-events "none"}
        [:& path-handler {:point last-p
                          :handler drag-handler
                          :edit-mode edit-mode
                          :zoom zoom}]])

     (when @hover-point
       [:g.hover-point
        [:& path-point {:position @hover-point
                        :edit-mode edit-mode
                        :new-point? true
                        :start-path? start-p?
                        :zoom zoom}]])

     (for [position points]
       (let [show-handler?
             (fn [[index prefix]]
               (let [handler-position (upc/handler->point content index prefix)]
                 (not= position handler-position)))

             pos-handlers (get handlers position)
             point-selected? (contains? selected-points (get point->base position))
             point-hover? (contains? hover-points (get point->base position))
             last-p? (= last-point (get point->base position))

             pos-handlers (->> pos-handlers (filter show-handler?))
             curve? (boolean (seq pos-handlers))]

         [:g.path-node
          [:g.point-handlers {:pointer-events (when (= edit-mode :draw) "none")}
           (for [[index prefix] pos-handlers]
             (let [handler-position (upc/handler->point content index prefix)
                   handler-hover? (contains? hover-handlers [index prefix])
                   moving-handler? (= handler-position moving-handler)
                   matching-handler? (matching-handler? content position pos-handlers)]
               [:& path-handler {:point position
                                 :handler handler-position
                                 :index index
                                 :prefix prefix
                                 :zoom zoom
                                 :hover? handler-hover?
                                 :snap-angle? (and moving-handler? matching-handler?)
                                 :edit-mode edit-mode}]))]
          [:& path-point {:position position
                          :zoom zoom
                          :edit-mode edit-mode
                          :selected? point-selected?
                          :hover? point-hover?
                          :last-p? last-p?
                          :start-path? start-p?
                          :curve? curve?}]]))

     (when prev-handler
       [:g.prev-handler {:pointer-events "none"}
        [:& path-handler {:point last-p
                          :edit-mode edit-mode
                          :handler prev-handler
                          :zoom zoom}]])

     (when show-snap?
       [:g.path-snap {:pointer-events "none"}
        [:& path-snap {:selected snap-selected
                       :points snap-points
                       :zoom zoom}]])]))

