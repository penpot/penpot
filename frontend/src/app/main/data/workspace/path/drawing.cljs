;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.drawing
  (:require
   [app.common.geom.point :as gpt]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.spec :as spec]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.tools :as tools]
   [app.main.streams :as ms]
   [app.util.geom.path :as ugp]
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
            shape (get-in state (st/get-path state))
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
              (update-in (st/get-path state) helpers/append-node position last-point prev-handler))
          state)))))

(defn start-drag-handler []
  (ptk/reify ::start-drag-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (st/get-path state :content))
            index (dec (count content))
            command (get-in state (st/get-path state :content index :command))

            make-curve
            (fn [command]
              (let [params (ugp/make-curve-params
                            (get-in content [index :params])
                            (get-in content [(dec index) :params]))]
                (-> command
                    (assoc :command :curve-to :params params))))]

        (cond-> state
          (= command :line-to)
          (update-in (st/get-path state :content index) make-curve))))))

(defn drag-handler [{:keys [x y alt? shift?]}]
  (ptk/reify ::drag-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            shape (get-in state (st/get-path state))
            content (:content shape)
            index (dec (count content))
            node-position (ugp/command->point (nth content index))
            handler-position (cond-> (gpt/point x y)
                               shift? (helpers/position-fixed-angle node-position))
            {dx :x dy :y} (gpt/subtract handler-position node-position)
            match-opposite? (not alt?)
            modifiers (helpers/move-handler-modifiers content (inc index) :c1 match-opposite? dx dy)]
        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
            (assoc-in [:workspace-local :edit-path id :prev-handler] handler-position)
            (assoc-in [:workspace-local :edit-path id :drag-handler] handler-position))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (update-in (st/get-path state :content) ugp/apply-content-modifiers modifiers)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (st/get-path state) helpers/update-selrect))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (st/get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler))))))

(declare close-path-drag-end)

(defn close-path-drag-start [position]
  (ptk/reify ::close-path-drag-start
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (st/get-path-id state)
            zoom (get-in state [:workspace-local :zoom])
            start-position @ms/mouse-position

            stop-stream
            (->> stream (rx/filter #(or (helpers/end-path-event? %)
                                        (ms/mouse-up? %))))

            drag-events-stream
            (->> (streams/position-stream)
                 (rx/take-until stop-stream)
                 (rx/map #(drag-handler %)))]

        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           (rx/of (start-drag-handler))
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
    (watch [_ state stream]
      (let [start-point @ms/mouse-position
            zoom (get-in state [:workspace-local :zoom])
            mouse-up    (->> stream (rx/filter #(or (helpers/end-path-event? %)
                                                    (ms/mouse-up? %))))
            drag-events (->> (streams/position-stream)
                             (rx/take-until mouse-up)
                             (rx/map #(drag-handler %)))]

        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           (rx/of (start-drag-handler))
           drag-events
           (rx/of (finish-drag)))))))))

(defn make-node-events-stream
  [stream]
  (->> stream
       (rx/filter (ptk/type? ::close-path-drag-start))
       (rx/take 1)
       (rx/merge-map #(rx/empty))))

(defn make-drag-stream
  [stream down-event zoom]
  (let [mouse-up    (->> stream (rx/filter #(or (helpers/end-path-event? %)
                                                (ms/mouse-up? %))))
        drag-events (->> (streams/position-stream)
                         (rx/take-until mouse-up)
                         (rx/map #(drag-handler %)))]

    (rx/concat
     (rx/of (add-node down-event))
     (streams/drag-stream
      (rx/concat
       (rx/of (start-drag-handler))
       drag-events
       (rx/of (finish-drag)))))))

(defn handle-drawing-path
  [id]
  (ptk/reify ::handle-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :edit-mode] :draw))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [zoom            (get-in state [:workspace-local :zoom])
            mouse-down      (->> stream (rx/filter ms/mouse-down?))
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
                            (make-drag-stream stream % zoom))))]

        (rx/concat
         (rx/of (common/init-path))
         (rx/merge mousemove-events
                   mousedown-events)
         (rx/of (common/finish-path "after-events")))))))


(defn setup-frame-path []
  (ptk/reify ::setup-frame-path
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dwc/lookup-page-objects state)
            content (get-in state [:workspace-drawing :object :content] [])
            position (get-in content [0 :params] nil)
            frame-id  (cp/frame-id-by-position objects position)]
        (-> state
            (assoc-in [:workspace-drawing :object :frame-id] frame-id))))))

(defn handle-new-shape-result [shape-id]
  (ptk/reify ::handle-new-shape-result
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state [:workspace-drawing :object :content] [])]
        (us/verify ::spec/content content)
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/of (setup-frame-path)
                  dwdc/handle-finish-drawing
                  (dwc/start-edition-mode shape-id)
                  (change-edit-mode :draw))))))

(defn handle-new-shape
  "Creates a new path shape"
  []
  (ptk/reify ::handle-new-shape
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
           (rx/of (handle-drawing-path id))
           (->> stream
                (rx/filter (ptk/type? ::common/finish-path))
                (rx/take 1)
                (rx/merge-map #(rx/of (check-changed-content)))))
          (rx/empty))))))

(defn check-changed-content []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (st/get-path-id state)
            content (get-in state (st/get-path state :content))
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
    (watch [_ state stream]
      (let [id (st/get-path-id state)]
        (cond
          (and id (= :move mode)) (rx/of (common/finish-path "change-edit-mode"))
          (and id (= :draw mode)) (rx/of (start-draw-mode))
          :else (rx/empty))))))
