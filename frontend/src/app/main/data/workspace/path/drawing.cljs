;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.drawing
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.path.commands :as upc]
   [app.common.path.shapes-to-path :as upsp]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common :refer [content?]]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(declare change-edit-mode)

(defn preview-next-point [{:keys [x y shift?]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            fix-angle? shift?
            last-point (get-in state [:workspace-local :edit-path id :last-point])
            position (cond-> (gpt/point x y)
                       fix-angle? (helpers/position-fixed-angle last-point))
            shape (st/get-path state)
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            command (helpers/next-node shape position last-point prev-handler)]
        (assoc-in state [:workspace-local :edit-path id :preview] command)))))

(defn add-node [{:keys [x y shift?]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            fix-angle? shift?
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            position (cond-> (gpt/point x y)
                       fix-angle? (helpers/position-fixed-angle last-point))]
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
             position (or position (upc/command->point (nth content (dec index))))

             old-handler (upc/handler->point content index prefix)

             handler-position (cond-> (gpt/point x y)
                                shift? (helpers/position-fixed-angle position))

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
                        (upc/apply-content-modifiers modifiers))

            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (st/set-content content)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (st/get-path-location state) helpers/update-selrect))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler)
               (undo/merge-head))))))

(declare close-path-drag-end)

(defn close-path-drag-start [position]
  (ptk/reify ::close-path-drag-start
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stop-stream
            (->> stream (rx/filter #(or (helpers/end-path-event? %)
                                        (ms/mouse-up? %))))

            content (st/get-path state :content)

            handlers (-> (upc/content->handlers content)
                         (get position))

            [idx prefix] (when (= (count handlers) 1)
                           (first handlers))

            drag-events-stream
            (->> (streams/position-stream)
                 (rx/take-until stop-stream)
                 (rx/map #(drag-handler position idx prefix %)))]

        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           drag-events-stream
           (rx/of (finish-drag))
           (rx/of (close-path-drag-end))))
         (rx/of (common/finish-path "close-path")))))))

(defn close-path-drag-end []
  (ptk/reify ::close-path-drag-end
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id] dissoc :prev-handler)))))

(defn start-path-from-point [position]
  (ptk/reify ::start-path-from-point
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [mouse-up    (->> stream (rx/filter #(or (helpers/end-path-event? %)
                                                    (ms/mouse-up? %))))
            drag-events (->> (streams/position-stream)
                             (rx/take-until mouse-up)
                             (rx/map #(drag-handler %)))]

        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           drag-events
           (rx/of (finish-drag)))))))))

(defn make-node-events-stream
  [stream]
  (->> stream
       (rx/filter (ptk/type? ::close-path-drag-start))
       (rx/take 1)
       (rx/merge-map #(rx/empty))))

(defn make-drag-stream
  [stream down-event]
  (let [mouse-up    (->> stream (rx/filter #(or (helpers/end-path-event? %)
                                                (ms/mouse-up? %))))

        drag-events (->> (streams/position-stream)
                         (rx/take-until mouse-up)
                         (rx/map #(drag-handler %)))]

    (rx/concat
     (rx/of (add-node down-event))
     (streams/drag-stream
      (rx/concat
       drag-events
       (rx/of (finish-drag)))))))

(defn handle-drawing-path
  [_id]
  (ptk/reify ::handle-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :edit-mode] :draw))))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [mouse-down      (->> stream (rx/filter ms/mouse-down?))
            end-path-events (->> stream (rx/filter helpers/end-path-event?))

            ;; Mouse move preview
            mousemove-events
            (->> (streams/position-stream)
                 (rx/take-until end-path-events)
                 (rx/map #(preview-next-point %)))

            ;; From mouse down we can have: click, drag and double click
            mousedown-events
            (->> mouse-down
                 (rx/take-until end-path-events)
                 (rx/with-latest merge (streams/position-stream))

                 ;; We change to the stream that emits the first event
                 (rx/switch-map
                  #(rx/race (make-node-events-stream stream)
                            (make-drag-stream stream %))))]

        (rx/concat
         (rx/of (undo/start-path-undo))
         (rx/of (common/init-path))
         (rx/merge mousemove-events
                   mousedown-events)
         (rx/of (common/finish-path "after-events")))))))


(defn setup-frame-path []
  (ptk/reify ::setup-frame-path
    ptk/UpdateEvent
    (update [_ state]
      (let [objects      (wsh/lookup-page-objects state)
            content      (get-in state [:workspace-drawing :object :content] [])
            position     (gpt/point (get-in content [0 :params] nil))
            frame-id     (ctst/top-nested-frame objects position)
            flex-layout? (ctl/flex-layout? objects frame-id)
            drop-index   (when flex-layout? (gsl/get-drop-index frame-id objects position))]
        (-> state
            (assoc-in [:workspace-drawing :object :frame-id] frame-id)
            (cond-> (some? drop-index)
              (update-in [:workspace-drawing :object] with-meta {:index drop-index})))))))

(defn handle-new-shape-result [shape-id]
  (ptk/reify ::handle-new-shape-result
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state [:workspace-drawing :object :content] [])]
        (dm/assert! (content? content))
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [content (get-in state [:workspace-drawing :object :content] [])]
        (if (seq content)
          (rx/of (setup-frame-path)
                 (dwdc/handle-finish-drawing)
                 (dwe/start-edition-mode shape-id)
                 (change-edit-mode :draw))
          (rx/of (dwdc/handle-finish-drawing)))))))

(defn handle-new-shape
  "Creates a new path shape"
  []
  (ptk/reify ::handle-new-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :snap-toggled] false))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape-id (get-in state [:workspace-drawing :object :id])]
        (rx/concat
         (rx/of (handle-drawing-path shape-id))
         (->> stream
              (rx/filter (ptk/type? ::common/finish-path))
              (rx/take 1)
              (rx/observe-on :async)
              (rx/map #(handle-new-shape-result shape-id))))))))

(declare check-changed-content)

(defn start-draw-mode []
  (ptk/reify ::start-draw-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-data :pages-index page-id :objects id :content])]
        (-> state
            (assoc-in [:workspace-local :edit-path id :old-content] old-content))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            edit-mode (get-in state [:workspace-local :edit-path id :edit-mode])]
        (if (= :draw edit-mode)
          (rx/concat
           (rx/of (dch/update-shapes [id] upsp/convert-to-path))
           (rx/of (handle-drawing-path id))
           (->> stream
                (rx/filter (ptk/type? ::common/finish-path))
                (rx/take 1)
                (rx/merge-map #(rx/of (check-changed-content)))))
          (rx/empty))))))

(defn check-changed-content []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            mode (get-in state [:workspace-local :edit-path id :edit-mode])]

        (cond
          (not= content old-content) (rx/of (changes/save-path-content)
                                            (start-draw-mode))
          (= mode :draw) (rx/of :interrupt)
          :else (rx/of (common/finish-path "changed-content")))))))

(defn change-edit-mode [mode]
  (ptk/reify ::change-edit-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (cond-> state
          id (assoc-in [:workspace-local :edit-path id :edit-mode] mode))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)]
        (cond
          (and id (= :move mode)) (rx/of (common/finish-path "change-edit-mode"))
          (and id (= :draw mode)) (rx/of (start-draw-mode))
          :else (rx/empty))))))

(defn reset-last-handler
  []
  (ptk/reify ::reset-last-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :prev-handler] nil))))))

