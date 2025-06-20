;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.path.segment :as path.segment]
   [app.main.data.workspace.path :as drp]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [clojure.set :refer [map-invert]]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def point-radius 5)
(def point-radius-selected 4)
(def point-radius-active-area 15)
(def point-radius-stroke-width 1)

(def handler-side 6)
(def handler-stroke-width 1)

(def path-preview-dasharray 4)
(def path-snap-stroke-width 1)

(def accent-color "var(--color-accent-tertiary)")
(def secondary-color "var(--color-accent-quaternary)")
(def black-color "var(--app-black)")
(def white-color "var(--app-white)")
(def gray-color "var(--df-secondary)")

(mf/defc path-point*
  {::mf/private true}
  [{:keys [position zoom edit-mode is-hover is-selected is-preview is-start-path is-last is-new is-curve]}]
  (let [{:keys [x y]} position

        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/path-pointer-enter position))))

        on-leave
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/path-pointer-leave position))))

        on-pointer-down
        (fn [event]
          (dom/stop-propagation event)
          (dom/prevent-default event)

          ;; FIXME: revisit this, using meta here breaks equality checks
          (when (and is-new (some? (meta position)))
            (st/emit! (drp/create-node-at-position (meta position))))

          (let [is-shift (kbd/shift? event)
                is-mod   (kbd/mod? event)]
            (cond
              is-last
              (st/emit! (drp/reset-last-handler))

              (and is-move is-mod (not is-curve))
              (st/emit! (drp/make-curve position))

              (and is-move is-mod is-curve)
              (st/emit! (drp/make-corner position))

              is-move
              ;; If we're dragging a selected item we don't change the selection
              (st/emit! (drp/start-move-path-point position is-shift))

              (and is-draw is-start-path)
              (st/emit! (drp/start-path-from-point position))

              (and is-draw (not is-start-path))
              (st/emit! (drp/close-path-drag-start position)))))]

    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (if ^boolean is-active
            (/ point-radius zoom)
            (/ point-radius-selected zoom))
       :style {:stroke-width (/ point-radius-stroke-width zoom)
               :stroke (cond ^boolean is-active black-color
                             ^boolean is-preview secondary-color
                             :else accent-color)
               :fill (cond is-selected accent-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               :pointer-events (when-not ^boolean is-preview "visible")
               :class (cond ^boolean is-draw (cur/get-static "pen-node")
                            ^boolean is-move (cur/get-static "pointer-node"))
               :style {:stroke-width 0
                       :fill "none"}}]]))

;; FIXME: is-selected prop looks unused

(mf/defc path-handler*
  {::mf/private true}
  [{:keys [index prefix point handler zoom is-selected is-hover edit-mode snap-angle]}]
  (let [x       (dm/get-prop handler :x)
        y       (dm/get-prop handler :y)
        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (mf/deps index prefix)
         (fn [_] (st/emit! (drp/path-handler-enter index prefix))))

        on-leave
        (mf/use-fn
         (mf/deps index prefix)
         (fn [_] (st/emit! (drp/path-handler-leave index prefix))))

        on-pointer-down
        (mf/use-fn
         (mf/deps index prefix is-move)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)

           (when ^boolean is-move
             (st/emit! (drp/start-move-handler index prefix)))))]

    [:g.handler {:pointer-events (if ^boolean is-draw "none" "visible")}
     [:line
      {:x1 (:x point)
       :y1 (:y point)
       :x2 x
       :y2 y
       :style {:stroke (if ^boolean is-hover
                         black-color
                         gray-color)
               :stroke-width (/ point-radius-stroke-width zoom)}}]

     (when ^boolean snap-angle
       [:line
        {:x1 (:x point)
         :y1 (:y point)
         :x2 x
         :y2 y
         :style {:stroke secondary-color
                 :stroke-width (/ point-radius-stroke-width zoom)}}])

     [:rect
      {:x (- x (/ handler-side 2 zoom))
       :y (- y (/ handler-side 2 zoom))
       :width (/ handler-side zoom)
       :height (/ handler-side zoom)

       :style {:stroke-width (/ handler-stroke-width zoom)
               :stroke (cond ^boolean is-active black-color
                             :else accent-color)
               :fill (cond ^boolean is-selected accent-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               :class (when ^boolean is-move
                        (cur/get-static "pointer-move"))
               :style {:fill "none"
                       :stroke-width 0}}]]))

(mf/defc path-preview*
  {::mf/private true}
  [{:keys [zoom segment from]}]

  (let [path
        (when (not= :move-to (:command segment))
          (let [segments [{:command :move-to
                           :params from}]
                segments (conj segments segment)]
            (path/content segments)))

        position
        (mf/with-memo [segment]
          ;; FIXME: use a helper from common for this
          (gpt/point (:params segment)))]

    [:g.preview {:style {:pointer-events "none"}}
     (when (some? path)
       [:path {:style {:fill "none"
                       :stroke black-color
                       :stroke-width (/ handler-stroke-width zoom)
                       :stroke-dasharray (/ path-preview-dasharray zoom)}
               :d (str path)}])

     [:> path-point* {:position position
                      :is-preview true
                      :zoom zoom}]]))

(mf/defc path-snap*
  {::mf/private true}
  [{:keys [selected points zoom]}]
  (let [ranges
        (mf/with-memo [selected points]
          (snap/create-ranges points selected))

        snap-matches
        (snap/get-snap-delta-match selected ranges (/ 1 zoom))

        matches
        (concat (second (:x snap-matches)) (second (:y snap-matches)))]

    [:g.snap-paths
     (for [[idx [from to]] (d/enumerate matches)]
       [:line {:key (dm/str "snap-" idx "-" from "-" to)
               :x1 (:x from)
               :y1 (:y from)
               :x2 (:x to)
               :y2 (:y to)
               :style {:stroke secondary-color
                       :stroke-width (/ path-snap-stroke-width zoom)}}])]))

(defn- matching-handler? [content node handlers]
  (when (= 2 (count handlers))
    (let [[[i1 p1] [i2 p2]] handlers
          p1 (path.segment/get-handler-point content i1 p1)
          p2 (path.segment/get-handler-point content i2 p2)

          v1 (gpt/to-vec node p1)
          v2 (gpt/to-vec node p2)

          angle (gpt/angle-with-other v1 v2)]
      (<= (- 180 angle) 0.1))))

(mf/defc path-editor*
  [{:keys [shape zoom state]}]
  (let [hover-point   (mf/use-state nil)
        editor-ref    (mf/use-ref nil)

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
                snap-toggled]}
        state

        selected-points
        (or selected-points #{})

        base-content
        (get shape :content)

        base-points
        (mf/with-memo [base-content]
          (path/get-points base-content))

        content
        (mf/with-memo [base-content content-modifiers]
          (path/apply-content-modifiers base-content content-modifiers))

        content-points
        (mf/with-memo [content]
          (path/get-points content))

        point->base (->> (map hash-map content-points base-points) (reduce merge))
        base->point (map-invert point->base)

        points
        (mf/with-memo [content-points]
          (into #{} content-points))

        last-p
        (->> content last path.helpers/segment->point)

        handlers
        (mf/with-memo [content]
          (path.segment/get-handlers content))

        is-path-start
        (not (some? last-point))

        show-snap?
        (and ^boolean snap-toggled
             (or (some? drag-handler)
                 (some? preview)
                 (some? moving-handler)
                 moving-nodes))]

    (mf/with-layout-effect [edit-mode]
      (let [key (events/listen (dom/get-root) "dblclick"
                               #(when (= edit-mode :move)
                                  (st/emit! :interrupt)))]
        #(events/unlistenByKey key)))

    (hooks/use-stream
     ms/mouse-position
     (mf/deps base-content zoom)
     (fn [position]
       (when-let [point (path.segment/closest-point base-content position (/ 0.01 zoom))]
         (reset! hover-point (when (< (gpt/distance position point) (/ 10 zoom)) point)))))

    [:g.path-editor {:ref editor-ref}
     [:path {:d (.toString content)
             :style {:fill "none"
                     :stroke accent-color
                     :strokeWidth (/ 1 zoom)}}]
     (when (and preview (not drag-handler))
       [:> path-preview* {:segment preview
                          :from last-p
                          :zoom zoom}])

     (when (and drag-handler last-p)
       [:g.drag-handler {:pointer-events "none"}
        [:> path-handler* {:point last-p
                           :handler drag-handler
                           :edit-mode edit-mode
                           :zoom zoom}]])

     (when @hover-point
       [:g.hover-point
        [:> path-point* {:position @hover-point
                         :edit-mode edit-mode
                         :is-new true
                         :is-start-path is-path-start
                         :zoom zoom}]])

     (for [position points]
       (let [pos-x (dm/get-prop position :x)
             pos-y (dm/get-prop position :y)

             show-handler?
             (fn [[index prefix]]
               ;; FIXME: get-handler-point is executed twice for each
               ;; render, this can be optimized
               (let [handler-position (path.segment/get-handler-point content index prefix)]
                 (not= position handler-position)))

             position-handlers
             (->> (get handlers position)
                  (filter show-handler?)
                  (not-empty))

             point-selected?
             (contains? selected-points (get point->base position))

             point-hover?
             (contains? hover-points (get point->base position))

             is-last
             (= last-point (get point->base position))

             is-curve
             (boolean position-handlers)]

         [:g.path-node {:key (dm/str pos-x "-" pos-y)}
          [:g.point-handlers {:pointer-events (when (= edit-mode :draw) "none")}
           (for [[hindex prefix] position-handlers]
             (let [handler-position  (path.segment/get-handler-point content hindex prefix)
                   handler-hover?    (contains? hover-handlers [hindex prefix])
                   moving-handler?   (= handler-position moving-handler)
                   matching-handler? (matching-handler? content position position-handlers)]

               (when (and position handler-position)
                 [:> path-handler*
                  {:key (dm/str hindex "-" (d/name prefix))
                   :point position
                   :handler handler-position
                   :index hindex
                   :prefix prefix
                   :zoom zoom
                   :is-hover handler-hover?
                   :snap-angle (and moving-handler? matching-handler?)
                   :edit-mode edit-mode}])))]

          [:> path-point* {:position position
                           :zoom zoom
                           :edit-mode edit-mode
                           :is-selected point-selected?
                           :is-hover point-hover?
                           :is-last is-last
                           :is-start-path is-path-start
                           :is-curve is-curve}]]))

     (when (and prev-handler last-p)
       [:g.prev-handler {:pointer-events "none"}
        [:> path-handler*
         {:point last-p
          :edit-mode edit-mode
          :handler prev-handler
          :zoom zoom}]])

     (when ^boolean show-snap?
       (let [[snap-selected snap-points]
             (cond
               (some? drag-handler) [#{drag-handler} points]
               (some? preview) [#{(path.helpers/segment->point preview)} points]
               (some? moving-handler) [#{moving-handler} points]
               :else
               [(->> selected-points (map base->point) (into #{}))
                (->> points (remove selected-points) (into #{}))])]
         [:g.path-snap {:pointer-events "none"}
          [:> path-snap* {:selected snap-selected
                          :points snap-points
                          :zoom zoom}]]))]))

