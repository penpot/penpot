;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.edition
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.selection :as selection]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.drawing :as drawing]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.streams :as ms]
   [app.util.geom.path :as ugp]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn modify-point [index prefix dx dy]
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers (inc index)] assoc
                       :c1x dx :c1y dy)
            (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                       :x dx :y dy :c2x dx :c2y dy))))))

(defn modify-handler [id index prefix dx dy match-opposite?]
  (ptk/reify ::modify-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (st/get-path state :content))
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
            point (gpt/point (+ (get-in content [index :params cx]) dx)
                             (+ (get-in content [index :params cy]) dy))
            opposite-index (ugp/opposite-index content index prefix)]
        (cond-> state
          :always
          (-> (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                         cx dx cy dy)
              (assoc-in [:workspace-local :edit-path id :moving-handler] point))

          (and match-opposite? opposite-index)
          (update-in [:workspace-local :edit-path id :content-modifiers opposite-index] assoc
                     ocx (- dx) ocy (- dy)))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (st/get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (st/get-path state))
            content-modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])

            content (:content shape)
            new-content (ugp/apply-content-modifiers content content-modifiers)

            old-points (->> content ugp/content->points)
            new-points (->> new-content ugp/content->points)
            point-change (->> (map hash-map old-points new-points) (reduce merge))

            [rch uch] (changes/generate-path-changes page-id shape (:content shape) new-content)]

        (rx/of (dwc/commit-changes rch uch {:commit-local? true})
               (selection/update-selection point-change)
               (fn [state] (update-in state [:workspace-local :edit-path id] dissoc :content-modifiers :moving-nodes :moving-handler)))))))

(defn move-selected-path-point [from-point to-point]
  (letfn [(modify-content-point [content {dx :x dy :y} modifiers point]
            (let [point-indices (ugp/point-indices content point) ;; [indices]
                  handler-indices (ugp/handler-indices content point) ;; [[index prefix]]

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
                (reduce modify-handler $ handler-indices))))]

    (ptk/reify ::move-point
      ptk/UpdateEvent
      (update [_ state]
        (let [id (st/get-path-id state)
              content (get-in state (st/get-path state :content))
              delta (gpt/subtract to-point from-point)

              modifiers-reducer (partial modify-content-point content delta)

              points (get-in state [:workspace-local :edit-path id :selected-points] #{})

              modifiers (get-in state [:workspace-local :edit-path id :content-modifiers] {})
              modifiers (->> points
                             (reduce modifiers-reducer {}))]

          (-> state
              (assoc-in [:workspace-local :edit-path id :moving-nodes] true)
              (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers)))))))

(declare drag-selected-points)

(defn start-move-path-point
  [position shift?]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            selected? (contains? selected-points position)]
        (streams/drag-stream
         (rx/of
          (when-not selected? (selection/select-node position shift? "drag"))
          (drag-selected-points @ms/mouse-position))
         (rx/of (selection/select-node position shift? "click")))))))

(defn drag-selected-points
  [start-position]
  (ptk/reify ::drag-selected-points
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter ms/mouse-up?))
            zoom (get-in state [:workspace-local :zoom])
            id (get-in state [:workspace-local :edition])
            snap-toggled (get-in state [:workspace-local :edit-path id :snap-toggled])

            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})

            content (get-in state (st/get-path state :content))
            points (ugp/content->points content)]

        (rx/concat
         ;; This stream checks the consecutive mouse positions to do the draging
         (->> points
              (streams/move-points-stream snap-toggled start-position selected-points)
              (rx/take-until stopper)
              (rx/map #(move-selected-path-point start-position %)))
         (rx/of (apply-content-modifiers)))))))

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

            content (get-in state (st/get-path state :content))
            points (ugp/content->points content)

            opposite-index   (ugp/opposite-index content index prefix)
            opposite-prefix  (if (= prefix :c1) :c2 :c1)
            opposite-handler (-> content (get opposite-index) (ugp/get-handler opposite-prefix))

            point (-> content (get (if (= prefix :c1) (dec index) index)) (ugp/command->point))
            handler (-> content (get index) (ugp/get-handler prefix))

            current-distance (when opposite-handler (gpt/distance (ugp/opposite-handler point handler) opposite-handler))
            match-opposite? (and opposite-handler (mth/almost-zero? current-distance))
            snap-toggled (get-in state [:workspace-local :edit-path id :snap-toggled])]

        (streams/drag-stream
         (rx/concat
          (->> (streams/move-handler-stream snap-toggled start-point handler points)
               (rx/take-until (->> stream (rx/filter ms/mouse-up?)))
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
                     (and (not alt?) match-opposite?))))))
          (rx/concat (rx/of (apply-content-modifiers)))))))))

(declare stop-path-edit)

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [edit-path (get-in state [:workspace-local :edit-path id])]

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

(defn create-node-at-position
  [{:keys [from-p to-p t]}]
  (ptk/reify ::create-node-at-position
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state (st/get-path state :content) ugp/split-segments #{from-p to-p} t)))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (changes/save-path-content)))))
