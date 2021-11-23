;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.edition
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as upg]
   [app.common.path.commands :as upc]
   [app.common.path.shapes-to-path :as upsp]
   [app.common.path.subpaths :as ups]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.streams :as ms]
   [app.util.path.tools :as upt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn modify-handler [id index prefix dx dy match-opposite?]
  (ptk/reify ::modify-handler
    ptk/UpdateEvent
    (update [_ state]

      (let [content (st/get-path state :content)
            modifiers (helpers/move-handler-modifiers content index prefix false match-opposite? dx dy)
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            point (gpt/point (+ (get-in content [index :params cx]) dx)
                             (+ (get-in content [index :params cy]) dy))]

        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
            (assoc-in [:workspace-local :edit-path id :moving-handler] point))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (wsh/lookup-page-objects state)

            id (st/get-path-id state)
            page-id (:current-page-id state)
            shape (st/get-path state)
            content-modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])

            content (:content shape)
            new-content (upc/apply-content-modifiers content content-modifiers)

            old-points (->> content upg/content->points)
            new-points (->> new-content upg/content->points)
            point-change (->> (map hash-map old-points new-points) (reduce merge))]

        (when (and (some? new-content) (some? shape))
          (let [[rch uch] (changes/generate-path-changes objects page-id shape (:content shape) new-content)]
            (if (empty? new-content)
              (rx/of (dch/commit-changes {:redo-changes rch
                                          :undo-changes uch
                                          :origin it})
                     dwc/clear-edition-mode)
              (rx/of (dch/commit-changes {:redo-changes rch
                                          :undo-changes uch
                                          :origin it})
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
            content-modifiers (get-in state [:workspace-local :edit-path id :content-modifiers] {})
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
            delta (gpt/subtract to-point from-point)

            modifiers-reducer (partial modify-content-point content delta)

            points (get-in state [:workspace-local :edit-path id :selected-points] #{})

            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers] {})
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
      (let [id (get-in state [:workspace-local :edition])
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            selected? (contains? selected-points position)]
        (streams/drag-stream
         (rx/of
          (dch/update-shapes [id] upsp/convert-to-path)
          (when-not selected? (selection/select-node position shift?))
          (drag-selected-points @ms/mouse-position))
         (rx/of (selection/select-node position shift?)))))))

(defn drag-selected-points
  [start-position]
  (ptk/reify ::drag-selected-points
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter ms/mouse-up?))
            id (get-in state [:workspace-local :edition])
            snap-toggled (get-in state [:workspace-local :edit-path id :snap-toggled])

            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})

            content (st/get-path state :content)
            points (upg/content->points content)]

        (rx/concat
         ;; This stream checks the consecutive mouse positions to do the dragging
         (->> points
              (streams/move-points-stream snap-toggled start-position selected-points)
              (rx/take-until stopper)
              (rx/map #(move-selected-path-point start-position %)))
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
      (let [id (get-in state [:workspace-local :edition])]
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
        (let [id (get-in state [:workspace-local :edition])
              current-move (get-in state [:workspace-local :edit-path id :current-move])]
          (if (nil? current-move)
            (-> state
                (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
                (assoc-in [:workspace-local :edit-path id :current-move] same-event))
            state)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [id (get-in state [:workspace-local :edition])
              current-move (get-in state [:workspace-local :edit-path id :current-move])]
          (if (= same-event current-move)
            (let [points (get-in state [:workspace-local :edit-path id :selected-points] #{})

                  move-events (->> stream
                                   (rx/filter (ptk/type? ::move-selected))
                                   (rx/filter #(= direction (deref %))))

                  stopper (->> move-events (rx/debounce 100) (rx/take 1))

                  scale (if shift? (gpt/point 10) (gpt/point 1))

                  mov-vec (gpt/multiply (get-displacement direction) scale)]

              (rx/concat
               (rx/of (dch/update-shapes [id] upsp/convert-to-path))
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
      (let [id (get-in state [:workspace-local :edition])
            cx (d/prefix-keyword prefix :x)
            cy (d/prefix-keyword prefix :y)
            start-point @ms/mouse-position
            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            start-delta-x (get-in modifiers [index cx] 0)
            start-delta-y (get-in modifiers [index cy] 0)

            content (st/get-path state :content)
            points (upg/content->points content)

            point (-> content (get (if (= prefix :c1) (dec index) index)) (upc/command->point))
            handler (-> content (get index) (upc/get-handler prefix))

            [op-idx op-prefix] (upc/opposite-index content index prefix)
            opposite (upc/handler->point content op-idx op-prefix)

            snap-toggled (get-in state [:workspace-local :edit-path id :snap-toggled])]

        (streams/drag-stream
         (rx/concat
          (rx/of (dch/update-shapes [id] upsp/convert-to-path))
          (->> (streams/move-handler-stream snap-toggled start-point point handler opposite points)
               (rx/take-until (->> stream (rx/filter #(or (ms/mouse-up? %)
                                                          (streams/finish-edition? %)))))
               (rx/map
                (fn [{:keys [x y alt? shift?]}]
                  (let [pos (cond-> (gpt/point x y)
                              shift? (helpers/position-fixed-angle point))]
                    (modify-handler
                     id
                     index
                     prefix
                     (+ start-delta-x (- (:x pos) (:x start-point)))
                     (+ start-delta-y (- (:y pos) (:y start-point)))
                     (not alt?))))))
          (rx/concat (rx/of (apply-content-modifiers)))))))))

(declare stop-path-edit)

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [edit-path (get-in state [:workspace-local :edit-path id])
            content (st/get-path state :content)
            state (st/set-content state (ups/close-subpaths content))]
        (cond-> state
          (or (not edit-path) (= :draw (:edit-mode edit-path)))
          (assoc-in [:workspace-local :edit-path id] {:edit-mode :move
                                                      :selected #{}
                                                      :snap-toggled true})

          (and (some? edit-path) (= :move (:edit-mode edit-path)))
          (assoc-in [:workspace-local :edit-path id :edit-mode] :draw))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [mode (get-in state [:workspace-local :edit-path id :edit-mode])]
        (rx/concat
         (rx/of (undo/start-path-undo))
         (rx/of (drawing/change-edit-mode mode))
         (->> stream
              (rx/take-until (->> stream (rx/filter (ptk/type? ::start-path-edit))))
              (rx/filter #(= % :interrupt))
              (rx/take 1)
              (rx/map #(stop-path-edit))))))))

(defn stop-path-edit []
  (ptk/reify ::stop-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (update state :workspace-local dissoc :edit-path id)))))

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
        (rx/of (dch/update-shapes [id] upsp/convert-to-path)
               (split-segments event))))))
