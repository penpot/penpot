;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.edition
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as upg]
   [app.common.svg.path.command :as upc]
   [app.common.svg.path.shapes-to-path :as upsp]
   [app.common.svg.path.subpath :as ups]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [app.util.path.tools :as upt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn modify-handler [id index prefix dx dy match-opposite?]
  (ptk/reify ::modify-handler
    ptk/UpdateEvent
    (update [_ state]

      (let [content (st/get-path state :content)
            modifiers (helpers/move-handler-modifiers content index prefix false match-opposite? dx dy)
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            point (gpt/point (+ (dm/get-in content [index :params cx]) dx)
                             (+ (dm/get-in content [index :params cy]) dy))]

        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
            (assoc-in [:workspace-local :edit-path id :moving-handler] point))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)

            id (st/get-path-id state)
            page-id (:current-page-id state)
            shape (st/get-path state)
            content-modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers])

            content (:content shape)
            new-content (upc/apply-content-modifiers content content-modifiers)

            old-points (->> content upg/content->points)
            new-points (->> new-content upg/content->points)
            point-change (->> (map hash-map old-points new-points) (reduce merge))]

        (when (and (some? new-content) (some? shape))
          (let [changes (changes/generate-path-changes it objects page-id shape (:content shape) new-content)]
            (if (empty? new-content)
              (rx/of (dch/commit-changes changes)
                     (dwe/clear-edition-mode))
              (rx/of (dch/commit-changes changes)
                     (selection/update-selection point-change)
                     (fn [state] (update-in state [:workspace-local :edit-path id] dissoc :content-modifiers :moving-nodes :moving-handler))))))))))

(defn modify-content-point
  [content {dx :x dy :y} modifiers point]
  (let [point-indices (upc/point-indices content point) ;; [indices]
        handler-indices (upc/handler-indices content point) ;; [[index prefix]]

        modify-point
        (fn [modifiers index]
          (-> modifiers
              (update index assoc :x dx :y dy)))

        modify-handler
        (fn [modifiers [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> modifiers
                (update index assoc cx dx cy dy))))]

    (as-> modifiers $
      (reduce modify-point   $ point-indices)
      (reduce modify-handler $ handler-indices))))

(defn set-move-modifier
  [points move-modifier]
  (ptk/reify ::set-modifiers
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            modifiers-reducer (partial modify-content-point content move-modifier)
            content-modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
            content-modifiers (->> points
                                   (reduce modifiers-reducer content-modifiers))]

        (-> state
            (assoc-in [:workspace-local :edit-path id :content-modifiers] content-modifiers))))))

(defn move-selected-path-point [from-point to-point]
  (ptk/reify ::move-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            to-point (cond-> to-point
                       (:shift? to-point) (helpers/position-fixed-angle from-point))

            delta (gpt/subtract to-point from-point)

            modifiers-reducer (partial modify-content-point content delta)

            points (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})

            modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers] {})
            modifiers (->> points
                           (reduce modifiers-reducer modifiers))]

        (-> state
            (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
            (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers))))))

(declare drag-selected-points)

(defn start-move-path-point
  [position shift?]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (dm/get-in state [:workspace-local :edition])
            selected-points (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})
            selected? (contains? selected-points position)]
        (streams/drag-stream
         (rx/of
          (dwsh/update-shapes [id] upsp/convert-to-path)
          (when-not selected? (selection/select-node position shift?))
          (drag-selected-points @ms/mouse-position))
         (rx/of (selection/select-node position shift?)))))))

(defn drag-selected-points
  [start-position]
  (ptk/reify ::drag-selected-points
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (mse/drag-stopper stream)

            id (dm/get-in state [:workspace-local :edition])

            selected-points (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})

            start-position (apply min-key #(gpt/distance start-position %) selected-points)

            content (st/get-path state :content)
            points (upg/content->points content)]

        (rx/concat
         ;; This stream checks the consecutive mouse positions to do the dragging
         (->> points
              (streams/move-points-stream start-position selected-points)
              (rx/map #(move-selected-path-point start-position %))
              (rx/take-until stopper))
         (rx/of (apply-content-modifiers)))))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(defn finish-move-selected []
  (ptk/reify ::finish-move-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [id (dm/get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id] dissoc :current-move))))))

(defn move-selected
  [direction shift?]

  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::move-selected
      IDeref
      (-deref [_] direction)

      ptk/UpdateEvent
      (update [_ state]
        (let [id (dm/get-in state [:workspace-local :edition])
              current-move (dm/get-in state [:workspace-local :edit-path id :current-move])]
          (if (nil? current-move)
            (-> state
                (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
                (assoc-in [:workspace-local :edit-path id :current-move] same-event))
            state)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [id (dm/get-in state [:workspace-local :edition])
              current-move (dm/get-in state [:workspace-local :edit-path id :current-move])]
          ;; id can be null if we just selected the tool but we didn't start drawing
          (if (and id (= same-event current-move))
            (let [points (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})

                  move-events (->> stream
                                   (rx/filter (ptk/type? ::move-selected))
                                   (rx/filter #(= direction (deref %))))

                  stopper (->> move-events (rx/debounce 100) (rx/take 1))

                  scale (if shift? (gpt/point 10) (gpt/point 1))

                  mov-vec (gpt/multiply (get-displacement direction) scale)]

              (rx/concat
               (rx/of (dwsh/update-shapes [id] upsp/convert-to-path))
               (rx/merge
                (->> move-events
                     (rx/take-until stopper)
                     (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                     (rx/map #(set-move-modifier points %)))

                ;; First event is not read by the stream so we need to send it again
                (rx/of (move-selected direction shift?)))

               (rx/of (apply-content-modifiers)
                      (finish-move-selected))))
            (rx/empty)))))))

(defn start-move-handler
  [index prefix]
  (ptk/reify ::start-move-handler
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (dm/get-in state [:workspace-local :edition])
            cx (d/prefix-keyword prefix :x)
            cy (d/prefix-keyword prefix :y)

            modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers])
            start-delta-x (dm/get-in modifiers [index cx] 0)
            start-delta-y (dm/get-in modifiers [index cy] 0)

            content (st/get-path state :content)
            points (upg/content->points content)

            point (-> content (get (if (= prefix :c1) (dec index) index)) (upc/command->point))
            handler (-> content (get index) (upc/get-handler prefix))

            [op-idx op-prefix] (upc/opposite-index content index prefix)
            opposite (upc/handler->point content op-idx op-prefix)]

        (streams/drag-stream
         (rx/concat
          (rx/of (dwsh/update-shapes [id] upsp/convert-to-path))
          (->> (streams/move-handler-stream handler point handler opposite points)
               (rx/map
                (fn [{:keys [x y alt? shift?]}]
                  (let [pos (cond-> (gpt/point x y)
                              shift? (helpers/position-fixed-angle point))]
                    (modify-handler
                     id
                     index
                     prefix
                     (+ start-delta-x (- (:x pos) (:x handler)))
                     (+ start-delta-y (- (:y pos) (:y handler)))
                     (not alt?)))))
               (rx/take-until
                (rx/merge
                 (mse/drag-stopper stream)
                 (->> stream
                      (rx/filter streams/finish-edition?)))))

          (rx/concat (rx/of (apply-content-modifiers)))))))))

(declare stop-path-edit)

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dsh/lookup-page-objects state)
            edit-path (dm/get-in state [:workspace-local :edit-path id])
            content (st/get-path state :content)
            state (cond-> state
                    (cfh/path-shape? objects id)
                    (st/set-content (ups/close-subpaths content)))]
        (cond-> state
          (or (not edit-path) (= :draw (:edit-mode edit-path)))
          (assoc-in [:workspace-local :edit-path id] {:edit-mode :move
                                                      :selected #{}
                                                      :snap-toggled false})

          (and (some? edit-path) (= :move (:edit-mode edit-path)))
          (assoc-in [:workspace-local :edit-path id :edit-mode] :draw))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [mode (dm/get-in state [:workspace-local :edit-path id :edit-mode])
            stopper (->> stream
                         (rx/filter #(or
                                      (= (ptk/type %) ::dwe/clear-edition-mode)
                                      (= (ptk/type %) ::start-path-edit))))
            interrupt (->> stream (rx/filter #(= % :interrupt)) (rx/take 1))]
        (rx/concat
         (rx/of (undo/start-path-undo)
                (drawing/change-edit-mode mode))
         (->> interrupt
              (rx/map #(stop-path-edit id))
              (rx/take-until stopper)))))))

(defn stop-path-edit [id]
  (ptk/reify ::stop-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :edit-path id))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event :layout/update {:ids [id]})))))

(defn split-segments
  [{:keys [from-p to-p t]}]
  (ptk/reify ::split-segments
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :old-content] content)
            (st/set-content (-> content (upt/split-segments #{from-p to-p} t))))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (changes/save-path-content {:preserve-move-to true})))))

(defn create-node-at-position
  [event]
  (ptk/reify ::create-node-at-position
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)]
        (rx/of (dwsh/update-shapes [id] upsp/convert-to-path)
               (split-segments event))))))
