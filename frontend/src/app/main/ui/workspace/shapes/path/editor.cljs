;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.helpers :as dwp.helpers]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
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
(def selected-color "var(--app-pink)")

;; Hover cursors for each edit mode and modifier combination.

(defn- node-cursor
  [edit-mode {:keys [shift? mod? alt?]} is-selected any-node-selected?]
  (if (= edit-mode :draw)
    (cond
      ^boolean alt? "draw-remove"
      ^boolean mod? "move-handles"
      :else         "draw-node")
    (cond
      (and ^boolean mod? ^boolean alt?) "draw-remove"
      ^boolean mod?                     "move-handles"
      ^boolean alt?                     "draw-remove"
      (and ^boolean shift?
           ^boolean any-node-selected?) "move-add"
      ^boolean is-selected              "move-move"
      :else                             "move-node")))

(defn- segment-cursor
  [edit-mode {:keys [shift? mod? alt?]} is-selected insert-preview?]
  (if (= edit-mode :draw)
    (cond
      ^boolean alt? "draw-remove"
      ^boolean mod? "move-curve"
      :else         "draw-add")
    (cond
      (and ^boolean mod? ^boolean alt?) "draw-remove"
      ^boolean mod?                     "move-curve"
      ^boolean alt?                     "draw-add"
      ^boolean shift?                   "move-add"
      ^boolean insert-preview?          "draw-add"
      ^boolean is-selected              "move-move"
      :else                             nil)))

(defn- handler-cursor
  [edit-mode {:keys [shift? mod? alt?]} is-selected]
  (cond
    (and ^boolean mod? ^boolean alt?) "move"
    (or ^boolean mod? ^boolean alt?)  "move-remove"
    (and (= edit-mode :move)
         ^boolean shift?
         (not ^boolean is-selected))  "move-add"
    :else                             "move"))

(mf/defc path-point*
  {::mf/private true}
  [{:keys [index position zoom edit-mode is-hover is-selected is-preview is-new cursor]}]
  (let [{:keys [x y]} position

        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (mf/deps index)
         (fn [_]
           (when (some? index)
             (st/emit! (drp/path-pointer-enter index)))))

        on-leave
        (mf/use-fn
         (mf/deps index)
         (fn [_]
           (when (some? index)
             (st/emit! (drp/path-pointer-leave index)))))

        on-pointer-down
        (fn [event]
          (when (dom/left-mouse? event)
            (uwvv/capture-pointer event)
            (dom/stop-propagation event)
            (dom/prevent-default event)
            ;; Preview nodes store their split params as metadata.
            ;; FIXME: revisit this, using meta here breaks equality checks
            (if (and is-new (some? (meta position)))
              (st/emit! (drp/create-node-at-position (meta position)))
              (let [is-shift (kbd/shift? event)
                    is-alt   (kbd/alt? event)
                    is-mod   (kbd/mod? event)]
                (cond
                  is-move
                  (st/emit! (drp/start-move-path-point index is-shift is-alt is-mod))

                  is-draw
                  (st/emit! (drp/on-draw-node-pointer-down index position is-alt is-mod)))))))]

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
               :fill (cond is-selected selected-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               ;; Let insertion preview clicks reach the segment.
               :pointer-events (cond ^boolean is-preview nil
                                     ^boolean is-new "none"
                                     :else "visible")
               :class (when (some? cursor) (cur/get-static cursor))
               :style {:stroke-width 0
                       :fill "none"}}]]))

(mf/defc path-handler*
  {::mf/private true}
  [{:keys [index prefix point handler zoom is-selected is-hover snap-angle cursor on-grab]}]
  (let [x       (dm/get-prop handler :x)
        y       (dm/get-prop handler :y)

        ;; Placed handlers and handlers with `on-grab` are interactive.
        is-interactive
        (or (some? index)
            (some? on-grab))

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
         (mf/deps index prefix is-interactive on-grab)
         (fn [event]
           (when (and ^boolean is-interactive (dom/left-mouse? event))
             (uwvv/capture-pointer event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (if (some? on-grab)
               (on-grab event)
               (st/emit! (drp/start-move-handler index
                                                 prefix
                                                 (kbd/shift? event)
                                                 (kbd/alt? event)
                                                 (kbd/mod? event)))))))]

    [:g.handler {:pointer-events (if ^boolean is-interactive "visible" "none")}
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
               :class (when (and ^boolean is-interactive (some? cursor))
                        (cur/get-static cursor))
               :style {:fill "none"
                       :stroke-width 0}}]]))

(defn- segment-content
  [{:keys [from to segment]}]
  (path/content
   [{:command :move-to
     :params from}
    (if (= :close-path (:command segment))
      {:command :line-to
       :params to}
      segment)]))

(mf/defc path-segment*
  {::mf/private true}
  [{:keys [entry zoom edit-mode is-interactive is-selected is-hover cursor]}]
  (let [index    (:index entry)
        content  (mf/with-memo [entry] (segment-content entry))
        is-active (or ^boolean is-selected ^boolean is-hover)
        on-enter (mf/use-fn
                  (mf/deps index)
                  (fn [_]
                    (st/emit! (drp/path-segment-enter index))))
        on-leave (mf/use-fn
                  (mf/deps index)
                  (fn [_]
                    (st/emit! (drp/path-segment-leave index))))
        on-pointer-down
        (mf/use-fn
         (mf/deps index is-interactive edit-mode)
         (fn [event]
           (when (and ^boolean is-interactive (dom/left-mouse? event))
             (uwvv/capture-pointer event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (if (= edit-mode :draw)
               (st/emit! (drp/on-draw-segment-pointer-down index
                                                           (kbd/alt? event)
                                                           (kbd/mod? event)))
               (st/emit! (drp/start-move-path-segment index
                                                      (kbd/shift? event)
                                                      (kbd/alt? event)
                                                      (kbd/mod? event)))))))]
    [:g.path-segment {:pointer-events (if ^boolean is-interactive "visible" "none")}
     (when ^boolean is-active
       [:path {:d (.toString content)
               :pointer-events "none"
               :style {:fill "none"
                       :stroke (if ^boolean is-selected
                                 selected-color
                                 accent-color)
                       :stroke-width (/ 2 zoom)}}])
     [:path {:d (.toString content)
             :on-pointer-down on-pointer-down
             :on-pointer-enter on-enter
             :on-pointer-leave on-leave
             :pointer-events "stroke"
             :class (when (some? cursor) (cur/get-static cursor))
             :style {:fill "none"
                     :stroke "transparent"
                     :stroke-width (/ point-radius-active-area zoom)}}]]))

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
  [{:keys [selected ranges zoom]}]
  (let [snap-matches
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
          p1 (path/get-handler-point content i1 p1)
          p2 (path/get-handler-point content i2 p2)

          v1 (gpt/to-vec node p1)
          v2 (gpt/to-vec node p2)

          angle (gpt/angle-with-other v1 v2)]
      (<= (- 180 angle) 0.1))))

(defn- use-path-modifiers
  "Tracks keyboard modifiers used by path cursors."
  []
  (let [modifiers* (mf/use-state {:shift? false :mod? false :alt? false})]
    (hooks/use-stream
     (mf/with-memo []
       (rx/combine-latest ms/keyboard-shift ms/keyboard-mod ms/keyboard-alt))
     (fn [[shift? mod? alt?]]
       (reset! modifiers* {:shift? (boolean shift?)
                           :mod?   (boolean mod?)
                           :alt?   (boolean alt?)})))
    (deref modifiers*)))

(defn- use-insertion-preview
  "Tracks the node insertion preview under the pointer."
  [content zoom move-mode? mid-points]
  (let [hover-point* (mf/use-state nil)]
    (hooks/use-stream
     (mf/with-memo []
       (rx/combine-latest ms/mouse-position ms/keyboard-mod ms/keyboard-shift ms/keyboard-alt))
     (mf/deps content zoom move-mode?)
     (fn [[position mod? shift? alt?]]
       (if (and ^boolean move-mode?
                (not shift?)
                (not mod?)
                (gpt/point? position))
         (reset! hover-point*
                 (dwp.helpers/insertion-point
                  content position
                  (/ dwp.helpers/segment-insert-threshold zoom)
                  (boolean alt?)
                  mid-points))
         (reset! hover-point* nil))))
    (deref hover-point*)))

(defn- create-snap-ranges
  "Builds snap ranges from stationary nodes."
  [content selected-nodes selected-segments include-all?]
  (let [points (if include-all?
                 (path/get-points content)
                 (let [moving-indices (into selected-nodes
                                            (dwp.helpers/segment-node-indices
                                             content selected-segments))
                       moving-positions (dwp.helpers/node-positions content moving-indices)]
                   (into [] (remove moving-positions) (path/get-points content))))]
    (snap/create-ranges points)))

(defn- snap-selected-points
  [content selected-nodes selected-segment-nodes drag-handler preview moving-handler]
  (cond
    (some? drag-handler)   #{drag-handler}
    (some? preview)        #{(path.helpers/segment->point preview)}
    (some? moving-handler) #{moving-handler}
    :else
    (dwp.helpers/node-positions
     content (into selected-nodes selected-segment-nodes))))

(mf/defc path-node*
  {::mf/private true}
  [{:keys [index position content handlers zoom edit-mode selected-nodes selected-handlers
           hover-nodes hover-handlers moving-handler modifiers drag-cursor
           any-node-selected]}]
  (let [show-handler?    (fn [[handler-index prefix]]
                           (not= position
                                 (path/get-handler-point content handler-index prefix)))
        point-handlers   (->> (get handlers position)
                              (filter show-handler?)
                              (not-empty))
        point-selected?  (contains? selected-nodes index)
        point-hover?     (contains? hover-nodes index)
        matching-handlers? (matching-handler? content position point-handlers)]
    [:g.path-node {:key (dm/str "node-" index)}
     [:g.point-handlers
      (for [[handler-index prefix] point-handlers]
        (let [handler-position (path/get-handler-point content handler-index prefix)
              handler-hover?  (contains? hover-handlers [handler-index prefix])
              handler-selected? (contains? selected-handlers [handler-index prefix])]
          (when (and position handler-position)
            [:> path-handler*
             {:key (dm/str handler-index "-" (d/name prefix))
              :point position
              :handler handler-position
              :index handler-index
              :prefix prefix
              :zoom zoom
              :is-selected handler-selected?
              :is-hover handler-hover?
              :snap-angle (and (= handler-position moving-handler) matching-handlers?)
              :edit-mode edit-mode
              :cursor (or drag-cursor
                          (handler-cursor edit-mode modifiers handler-selected?))}])))]

     [:> path-point* {:index index
                      :position position
                      :zoom zoom
                      :edit-mode edit-mode
                      :is-selected point-selected?
                      :is-hover point-hover?
                      :cursor (or drag-cursor
                                  (node-cursor edit-mode modifiers point-selected?
                                               any-node-selected))}]]))

(mf/defc path-editor*
  [{:keys [shape zoom state]}]
  (let [editor-ref    (mf/use-ref nil)

        {:keys [edit-mode
                drag-handler
                prev-handler
                preview
                content-modifiers
                selection
                moving-nodes
                moving-handler
                hover
                snap-toggled
                drag-cursor]}
        state

        move-mode?
        (= edit-mode :move)

        draw-mode?
        (= edit-mode :draw)

        modifiers
        (use-path-modifiers)

        selected-nodes    (get selection :nodes #{})
        selected-segments (get selection :segments #{})
        selected-handlers (get selection :handlers #{})
        hover-nodes       (get hover :nodes #{})
        hover-segments    (get hover :segments #{})
        hover-handlers    (get hover :handlers #{})

        any-node-selected?
        (boolean (seq selected-nodes))

        ;; Skip segment hit targets while dragging.
        dragging?
        (or (some? drag-cursor)
            (some? drag-handler))

        base-content
        (get shape :content)

        ;; Cache segment midpoints used by insertion previews.
        insertion-mid-points
        (mf/with-memo [base-content move-mode?]
          (when move-mode?
            (dwp.helpers/insertion-mid-points base-content)))

        hover-point
        (use-insertion-preview base-content zoom move-mode? insertion-mid-points)

        content
        (mf/with-memo [base-content content-modifiers]
          (path/apply-content-modifiers base-content content-modifiers))

        content-points
        (mf/with-memo [content]
          (path/get-points content))

        ;; Pair each node position with its content index.
        node-entries
        (mf/with-memo [content content-points]
          (mapv vector (dwp.helpers/node-indices content) content-points))

        segment-entries
        (mf/with-memo [content dragging?]
          (when-not dragging?
            (dwp.helpers/segment-entries content)))

        selected-segment-nodes
        (mf/with-memo [content selected-segments]
          (dwp.helpers/segment-node-indices content selected-segments))

        last-p
        (->> content last path.helpers/segment->point)

        handlers
        (mf/with-memo [content]
          (path/get-handlers content))

        ;; Build snap ranges from stationary nodes.
        snap-dragging-handler?
        (boolean (or (some? drag-handler)
                     (some? preview)
                     (some? moving-handler)))

        snap-ranges
        (mf/with-memo [base-content selected-nodes selected-segments snap-dragging-handler?]
          (create-snap-ranges
           base-content selected-nodes selected-segments snap-dragging-handler?))

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

    [:g.path-editor {:ref editor-ref}
     [:path {:d (.toString content)
             :style {:fill "none"
                     :stroke accent-color
                     :strokeWidth (/ 1 zoom)}}]
     (for [{:keys [index] :as entry} segment-entries]
       (let [is-selected (or (contains? selected-segments index)
                             ;; Select segments between selected endpoints.
                             (and (contains? selected-nodes (:from-index entry))
                                  (contains? selected-nodes (:to-index entry))))
             is-hover    (contains? hover-segments index)]
         [:> path-segment*
          {:key (dm/str "segment-" index)
           :entry entry
           :zoom zoom
           :edit-mode edit-mode
           :is-interactive (or ^boolean move-mode? ^boolean draw-mode?)
           :is-selected is-selected
           :is-hover is-hover
           :cursor (or drag-cursor
                       (segment-cursor edit-mode modifiers is-selected
                                       (and is-hover (some? hover-point))))}]))
     (when (and preview (not drag-handler))
       [:> path-preview* {:segment preview
                          :from last-p
                          :zoom zoom}])

     ;; Let insertion preview clicks reach the segment.
     (when (and ^boolean move-mode? (some? hover-point))
       [:g.hover-point {:pointer-events "none"}
        [:> path-point* {:position hover-point
                         :edit-mode edit-mode
                         :is-new true
                         :zoom zoom}]])

     (when (and drag-handler last-p)
       [:g.drag-handler {:pointer-events "none"}
        [:> path-handler* {:point last-p
                           :handler drag-handler
                           :edit-mode edit-mode
                           :zoom zoom}]])

     (for [[index position] node-entries]
       [:> path-node* {:key (dm/str "node-" index)
                       :index index
                       :position position
                       :content content
                       :handlers handlers
                       :zoom zoom
                       :edit-mode edit-mode
                       :selected-nodes selected-nodes
                       :selected-handlers selected-handlers
                       :hover-nodes hover-nodes
                       :hover-handlers hover-handlers
                       :moving-handler moving-handler
                       :modifiers modifiers
                       :drag-cursor drag-cursor
                       :any-node-selected any-node-selected?}])

     (when (and prev-handler last-p)
       [:g.prev-handler
        [:> path-handler*
         {:point last-p
          :edit-mode edit-mode
          :handler prev-handler
          :zoom zoom
          :on-grab (fn [_] (st/emit! (drp/start-move-prev-handler)))
          :cursor (or drag-cursor
                      (handler-cursor edit-mode modifiers false))}]])

     (when ^boolean show-snap?
       (let [snap-selected (snap-selected-points
                            content selected-nodes selected-segment-nodes
                            drag-handler preview moving-handler)]
         [:g.path-snap {:pointer-events "none"}
          [:> path-snap* {:selected snap-selected
                          :ranges snap-ranges
                          :zoom zoom}]]))]))
