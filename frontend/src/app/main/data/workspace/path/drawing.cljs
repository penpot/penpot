;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.drawing
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.edition :as edition]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.tools :as tools]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare close-path-drag-end)
(declare check-changed-content)
(declare change-edit-mode)

(defn start-created-path-edition
  [id]
  (ptk/reify ::start-created-path-edition
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwe/start-edition-mode id)
             (edition/start-path-edit id)
             (change-edit-mode :draw)))))

;; Draw-loop stop signals either restart the same path or exit drawing.

(defn restart-draw-loop?
  "True when drawing restarts on the same path."
  [event]
  (or (= (ptk/type event) ::common/finish-path)
      (and ^boolean (mse/mouse-event? event)
           ^boolean (mse/mouse-double-click-event? event))))

(defn- exit-draw-loop?
  "True when the draw loop exits."
  [event]
  (let [type (ptk/type event)]
    (or (= type ::dwe/clear-edition-mode)
        (= type ::dwpg/finalize-page)
        (dwe/interrupt? event))))

(defn- end-path-event?
  "True when the draw loop should stop."
  [event]
  (or (restart-draw-loop? event)
      (exit-draw-loop? event)))

(def ^:private draw-insert-threshold
  "Maximum screen distance for inserting a node on a segment."
  16)

(defn preview-next-point
  [{:keys [x y shift?]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            edit-path (get-in state [:workspace-local :edit-path id])]
        ;; Freeze the next-point preview during modifier drags.
        (if (seq (:content-modifiers edit-path))
          state
          (let [fix-angle? shift?
                {:keys [last-point prev-handler]} edit-path
                content    (st/get-path state :content)
                zoom       (dm/get-in state [:workspace-local :zoom] 1)
                raw-pos    @ms/mouse-position

                ;; Segment insertion uses the exact on-curve preview point.
                insert-point (when (and (seq (:segments (:hover edit-path)))
                                        (gpt/point? raw-pos))
                               (helpers/insertion-point
                                content raw-pos (/ draw-insert-threshold zoom) true))

                position   (cond
                             (some? insert-point)
                             insert-point

                             fix-angle?
                             (path.helpers/position-fixed-angle (gpt/point x y) last-point)

                             :else
                             (gpt/point x y))
                segment    (path/next-node content position last-point prev-handler)]
            (assoc-in state [:workspace-local :edit-path id :preview] segment)))))))

(defn add-node
  [{:keys [x y shift?]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            fix-angle? shift?
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            position (cond-> (gpt/point x y)
                       fix-angle? (path.helpers/position-fixed-angle last-point))]
        (if-not (= last-point position)
          (-> state
              (assoc-in  [:workspace-local :edit-path id :last-point] position)
              (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
              (update-in [:workspace-local :edit-path id] dissoc :preview)
              (update-in (st/get-path-location state) helpers/append-node position last-point prev-handler))
          state)))))

(defn drag-handler
  ([position]
   (drag-handler nil nil :c1 position))
  ([position index prefix {:keys [x y alt? shift?]}]
   (ptk/reify ::drag-handler
     ptk/UpdateEvent
     (update [_ state]
       (let [id (st/get-path-id state)
             content (st/get-path state :content)

             index (or index (count content))
             prefix (or prefix :c1)
             position (or position (path.helpers/segment->point (nth content (dec index))))

             old-handler (path/get-handler-point content index prefix)

             handler-position (cond-> (gpt/point x y)
                                shift? (path.helpers/position-fixed-angle position))

             {dx :x dy :y} (if (some? old-handler)
                             (gpt/add (gpt/to-vec old-handler position)
                                      (gpt/to-vec position handler-position))
                             (gpt/to-vec position handler-position))

             match-opposite? (not alt?)

             modifiers (helpers/move-handler-modifiers content index prefix match-opposite? match-opposite? dx dy)]
         (-> state
             (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
             (assoc-in [:workspace-local :edit-path id :drag-handler] handler-position)))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)

            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            content (-> (st/get-path state :content)
                        (path/apply-content-modifiers modifiers))

            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (st/set-content content)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (st/get-path-location state) path/update-geometry))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler)
               (undo/merge-head))))))

(defn drag-prev-handler
  "Moves the current node's forward handle while drawing."
  [{:keys [x y alt? shift?]}]
  (ptk/reify ::drag-prev-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id       (st/get-path-id state)
            content  (st/get-path state :content)
            index    (count content)
            position (path.helpers/segment->point (nth content (dec index)))

            handler-position
            (cond-> (gpt/point x y)
              shift? (path.helpers/position-fixed-angle position))

            dx       (- (:x handler-position) (:x position))
            dy       (- (:y handler-position) (:y position))

            ;; Alt leaves the opposite handle unchanged.
            rejoin?  (not alt?)

            modifiers (helpers/move-handler-modifiers content index :c1 false false rejoin? dx dy)]
        (-> state
            (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
            (assoc-in  [:workspace-local :edit-path id :content-modifiers] modifiers)
            (assoc-in  [:workspace-local :edit-path id :drag-handler] handler-position))))))

(defn start-move-prev-handler
  "Starts dragging the current node's forward handle."
  []
  (ptk/reify ::start-move-prev-handler
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/merge
                     (mse/drag-stopper stream)
                     (rx/filter end-path-event? stream))

            drag-events
            (->> (streams/position-stream state)
                 (rx/map drag-prev-handler)
                 (rx/take-until stopper))]
        (streams/drag-stream
         (rx/concat
          (rx/of (edition/set-drag-cursor "move-handles"))
          drag-events
          (rx/of (finish-drag))))))))

(defn close-path-drag-start
  ([position]
   (close-path-drag-start position "draw-node"))
  ([position cursor]
   (ptk/reify ::close-path-drag-start
     ptk/WatchEvent
     (watch [_ state stream]
       (let [content  (st/get-path state :content)
             handlers (-> (path/get-handlers content)
                          (get position))

             [idx prefix] (when (= (count handlers) 1)
                            (first handlers))

             drag-events-stream
             (->> (streams/position-stream state)
                  (rx/map #(drag-handler position idx prefix %))
                  (rx/take-until
                   (rx/merge
                    (mse/drag-stopper stream)
                    (rx/filter end-path-event? stream))))]

         (rx/concat
          (rx/of (add-node position))
          (streams/drag-stream
           (rx/concat
            (rx/of (edition/set-drag-cursor cursor))
            drag-events-stream
            (rx/of (finish-drag))
            (rx/of (close-path-drag-end))))
          (rx/of (common/finish-path))))))))

(defn close-path-drag-end []
  (ptk/reify ::close-path-drag-end
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id] dissoc :prev-handler)))))

(defn start-path-from-point
  ([position]
   (start-path-from-point position "draw-node"))
  ([position cursor]
   (ptk/reify ::start-path-from-point
     ptk/WatchEvent
     (watch [_ state stream]
       (let [stopper (rx/merge
                      (mse/drag-stopper stream)
                      (rx/filter end-path-event? stream))

             drag-events (->> (streams/position-stream state)
                              (rx/map #(drag-handler %))
                              (rx/take-until stopper))]
         (rx/concat
          (rx/of (add-node position))
          (streams/drag-stream
           (rx/concat
            (rx/of (edition/set-drag-cursor cursor))
            drag-events
            (rx/of (finish-drag))))))))))

(defn make-drag-stream
  [state stream down-event]

  (assert (gpt/point? down-event)
          "should be a point instance")

  (let [stopper (rx/merge
                 (mse/drag-stopper stream)
                 (rx/filter end-path-event? stream))

        drag-events
        (->> (streams/position-stream state)
             (rx/map #(drag-handler %))
             (rx/take-until stopper))]
    (rx/concat
     (rx/of (add-node down-event))
     (streams/drag-stream
      (rx/concat
       drag-events
       (rx/of (finish-drag)))))))

(defn- start-edition
  [_id]
  (ptk/reify ::start-edition
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id]
                   (fn [edit-state]
                     (-> edit-state
                         (assoc :edit-mode :draw)
                         ;; Keep explicit snap choices across draw restarts.
                         (update :snap-toggled (fnil identity true)))))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [mouse-down
            (->> stream
                 (rx/filter mse/mouse-event?)
                 (rx/filter mse/mouse-down-event?))

            end-stream
            (->> stream
                 (rx/filter end-path-event?)
                 (rx/share))

            stop-event
            (volatile! nil)

            stoper-stream
            (->> stream
                 (rx/filter (ptk/type? ::start-edition))
                 (rx/merge end-stream)
                 (rx/tap #(vreset! stop-event %))
                 (rx/share))

            ;; Mouse move preview
            mousemove-events
            (->> (streams/position-stream state)
                 (rx/map #(preview-next-point %)))

            ;; Viewport clicks add nodes; node clicks handle closing separately.
            mousedown-events
            (->> mouse-down
                 ;; We just ignore the mouse event and stream down the
                 ;; last position event
                 (rx/with-latest-from #(-> %2) (streams/position-stream state))
                 (rx/switch-map
                  #(make-drag-stream state stream %))
                 (rx/take-until end-stream))]

        (->> (rx/concat
              (rx/of (undo/start-path-undo))
              (->> (rx/merge mousemove-events
                             mousedown-events)
                   (rx/take-until stoper-stream))
              (->> (rx/of nil)
                   (rx/map (fn [_]
                             (ptk/data-event
                              ::end-edition
                              {:restart? (restart-draw-loop? @stop-event)}))))))))))

(defn setup-frame
  []
  (ptk/reify ::setup-frame
    ptk/UpdateEvent
    (update [_ state]
      (let [objects      (dsh/lookup-page-objects state)
            content      (get-in state [:workspace-drawing :object :content] [])

            ;; FIXME: use native operation for retrieve the first position
            position     (-> (nth content 0)
                             (get :params)
                             (gpt/point))

            frame-id     (->> (ctst/top-nested-frame objects position)
                              (ctn/get-first-valid-parent objects) ;; We don't want to change the structure of component copies
                              :id)
            flex-layout? (ctl/flex-layout? objects frame-id)
            drop-index   (when flex-layout? (gsl/get-drop-index frame-id objects position))]

        (update-in state [:workspace-drawing :object]
                   (fn [object]
                     (-> object
                         (assoc :frame-id frame-id)
                         (assoc :parent-id frame-id)
                         (cond-> (some? drop-index)
                           (with-meta {:index drop-index})))))))))

(defn- close-drawn-loops
  "Adds explicit close commands to completed loops."
  []
  (ptk/reify ::close-drawn-loops
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-drawing :object]
                        (fn [object]
                          (-> object
                              (update :content path/close-loops)
                              (path/update-geometry)))))))

(defn- handle-drawing-end
  [shape-id restart?]
  (ptk/reify ::handle-drawing-end
    ptk/UpdateEvent
    (update [_ state]
      (let [content (some-> (dm/get-in state [:workspace-drawing :object :content])
                            (path/check-content))]
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [content (dm/get-in state [:workspace-drawing :object :content])]
        (cond
          (and (> (count content) 1) restart?)
          (rx/of (common/finish-path)
                 (close-drawn-loops)
                 (setup-frame)
                 (dwdc/handle-finish-drawing)
                 (start-created-path-edition shape-id))

          (> (count content) 1)
          (rx/of (close-drawn-loops)
                 (setup-frame)
                 (dwdc/handle-finish-drawing)
                 (dwe/clear-edition-mode))

          :else
          (rx/of (dwdc/handle-finish-drawing)
                 (dwe/clear-edition-mode)))))))

(defn handle-drawing
  "Starts drawing a path."
  []
  (ptk/reify ::handle-new-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (cts/setup-shape {:type :path})]
        (update state :workspace-drawing assoc :object shape)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape-id (dm/get-in state [:workspace-drawing :object :id])]
        (rx/concat
         (rx/of (start-edition shape-id))
         (->> stream
              (rx/filter (ptk/type? ::end-edition))
              (rx/take 1)
              ;; Let the stop event settle before finishing the drawing.
              (rx/observe-on :async)
              (rx/map (fn [event]
                        (handle-drawing-end shape-id (:restart? (deref event)))))))))))

(declare start-draw-mode*)

(defn start-draw-mode
  []
  (ptk/reify ::start-draw-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id          (dm/get-in state [:workspace-local :edition])
            objects     (dsh/lookup-page-objects state)
            shape       (get objects id)
            drawing     (dm/get-in state [:workspace-drawing :object])
            old-content (dm/get-in state [:workspace-local :edit-path id :old-content])
            drawing     (or drawing
                            (some-> shape
                                    (path/convert-to-path objects)
                                    (update :content path/close-subpaths)
                                    (path/update-geometry)))]
        (cond-> state
          drawing
          (assoc-in [:workspace-drawing :object] drawing)

          (and drawing (nil? old-content))
          (assoc-in [:workspace-local :edit-path id :old-content] (:content drawing)))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (start-draw-mode*)))))

(defn start-draw-mode*
  []
  (ptk/reify ::start-draw-mode*
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (get state :workspace-local)
            id    (get local :edition)
            mode  (dm/get-in local [:edit-path id :edit-mode])]

        (if (= :draw mode)
          (rx/concat
           (rx/of (start-edition id))
           (->> stream
                (rx/filter (ptk/type? ::end-edition))
                (rx/take 1)
                (rx/mapcat (fn [event]
                             (if (:restart? (deref event))
                               (rx/of (common/finish-path)
                                      (check-changed-content)
                                      (start-draw-mode*))
                               (rx/empty))))))
          (rx/empty))))))

(defn- enter-draw-from-selected-node
  "Starts a new segment from the only selected node."
  [state id]
  (let [selection  (get (st/get-selection state id) :nodes #{})
        last-point (dm/get-in state [:workspace-local :edit-path id :last-point])
        content    (st/get-path state :content)]
    (if (and (nil? last-point)
             (= 1 (count selection))
             (some? content)
             (helpers/node? content (first selection)))
      (let [index    (first selection)
            pos      (helpers/node-position content index)
            last-idx (dec (count content))
            tip?     (and (= index last-idx)
                          (not= :close-path (:command (nth content index nil))))
            state    (assoc-in state [:workspace-local :edit-path id :last-point] pos)]
        (if tip?
          state
          (update-in state (st/get-path-location state)
                     (fn [shape]
                       (-> shape
                           (update :content path/append-segment
                                   {:command :move-to :params (select-keys pos [:x :y])})
                           (path/update-geometry))))))
      state)))

(defn change-edit-mode
  [mode]
  (ptk/reify ::change-edit-mode
    ptk/UpdateEvent
    (update [_ state]
      (if-let [id (dm/get-in state [:workspace-local :edition])]
        (cond-> (d/update-in-when state [:workspace-local :edit-path id] assoc :edit-mode mode)
          (= mode :draw) (enter-draw-from-selected-node id))
        state))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [id (dm/get-in state [:workspace-local :edition])]
        (let [mode (dm/get-in state [:workspace-local :edit-path id :edit-mode])]
          (case mode
            :move (rx/of (common/finish-path))
            :draw (rx/of (start-draw-mode))
            (rx/empty)))))))

(defn reset-last-handler
  []
  (ptk/reify ::reset-last-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :prev-handler] nil)))))

(defn on-draw-node-pointer-down
  "Handles node clicks and drags in draw mode."
  [index position alt? mod?]
  (ptk/reify ::on-draw-node-pointer-down
    ptk/WatchEvent
    (watch [_ state _]
      (let [id         (st/get-path-id state)
            content    (st/get-path state :content)
            node-pos   (when (and (some? content)
                                  (< index (count content))
                                  (helpers/node? content index))
                         (helpers/node-position content index))
            last-point (dm/get-in state [:workspace-local :edit-path id :last-point])
            pending-origin? (and (some? node-pos) (= last-point node-pos))]
        (cond
          (and mod? alt?)
          (rx/concat
           (rx/of (tools/remove-node-with-segments index))
           (if pending-origin?
             (rx/of (common/cancel-pending-segment))
             (rx/empty)))

          mod?
          (streams/drag-stream
           (rx/of (edition/set-drag-cursor "move-handles")
                  (edition/curve-config-node-drag index))
           (rx/of (tools/toggle-node-curve index)))

          alt?
          (if (some? node-pos)
            (rx/concat
             (rx/of (tools/remove-node node-pos))
             (if pending-origin?
               (rx/of (common/cancel-pending-segment))
               (rx/empty)))
            (rx/empty))

          (= last-point position)
          (rx/of (reset-last-handler))

          (nil? last-point)
          (rx/of (start-path-from-point position))

          :else
          (rx/of (close-path-drag-start position)))))))

(defn on-draw-segment-pointer-down
  "Handles segment clicks and drags in draw mode."
  [index alt? mod?]
  (ptk/reify ::on-draw-segment-pointer-down
    ptk/WatchEvent
    (watch [_ state _]
      (let [id         (st/get-path-id state)
            zoom       (dm/get-in state [:workspace-local :zoom] 1)
            content    (st/get-path state :content)
            position   @ms/mouse-position
            last-point (dm/get-in state [:workspace-local :edit-path id :last-point])]
        (cond
          alt?
          (let [entry         (d/seek #(= index (:index %)) (helpers/segment-entries content))
                pending-here? (and (some? last-point)
                                   (some? entry)
                                   (or (= last-point (:from entry))
                                       (= last-point (:to entry))))]
            (rx/concat
             (rx/of (tools/remove-segment index))
             (if pending-here?
               (rx/of (common/cancel-pending-segment))
               (rx/empty))))

          mod?
          (let [entry     (d/seek #(= index (:index %)) (helpers/segment-entries content))
                bendable? (and (some? entry)
                               (not= :close-path (:command (:segment entry))))]
            (streams/drag-stream
             (if bendable?
               (rx/of (edition/set-drag-cursor "move-curve")
                      (edition/bend-selected-segment index position))
               (rx/empty))
             (rx/of (tools/toggle-segment-curve index))))

          :else
          (let [insert-point (helpers/insertion-point
                              content position (/ draw-insert-threshold zoom) true)]
            (if (some? insert-point)
              (rx/concat
               (rx/of (edition/create-node-at-position (meta insert-point)))
               (if (some? last-point)
                 (rx/of (close-path-drag-start insert-point "draw-add"))
                 (rx/of (start-path-from-point insert-point "draw-add"))))
              (rx/empty))))))))

(defn check-changed-content
  []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            mode (get-in state [:workspace-local :edit-path id :edit-mode])
            empty-content? (empty? content)]

        (cond
          (and (not= content old-content) (not empty-content?))
          (rx/empty)

          ;; Exit through the path edition stop event.
          (= mode :draw)
          (rx/of (dwe/clear-edition-mode))

          :else
          (rx/of
           (common/finish-path)
           (dwdc/clear-drawing)))))))
