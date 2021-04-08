;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
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
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (st/get-path state :content))
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
            opposite-index (ugp/opposite-index content index prefix)]
        (cond-> state
          :always
          (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                     cx dx cy dy)

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
            new-content (ugp/apply-content-modifiers (:content shape) content-modifiers)
            [rch uch] (changes/generate-path-changes page-id shape (:content shape) new-content)]

        (rx/of (dwc/commit-changes rch uch {:commit-local? true})
               (fn [state] (update-in state [:workspace-local :edit-path id] dissoc :content-modifiers)))))))

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

          (assoc-in state [:workspace-local :edit-path id :content-modifiers] modifiers))))))

(defn start-move-path-point
  [position shift?]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [start-position @ms/mouse-position
            stopper (->> stream (rx/filter ms/mouse-up?))
            zoom (get-in state [:workspace-local :zoom])
            id (get-in state [:workspace-local :edition])
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            selected? (contains? selected-points position)

            content (get-in state (st/get-path state :content))
            points (ugp/content->points content)

            mouse-drag-stream
            (rx/concat
             ;; If we're dragging a selected item we don't change the selection
             (if selected?
               (rx/empty)
               (rx/of (selection/select-node position shift?)))

             ;; This stream checks the consecutive mouse positions to do the draging
             (->> (streams/position-stream points)
                  (rx/take-until stopper)
                  (rx/map #(move-selected-path-point start-position %)))
             (rx/of (apply-content-modifiers)))

            ;; When there is not drag we select the node
            mouse-click-stream
            (rx/of (selection/select-node position shift?))]

        (streams/drag-stream mouse-drag-stream mouse-click-stream)))))

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
            match-opposite? (and opposite-handler (mth/almost-zero? current-distance))]

        (streams/drag-stream
         (rx/concat
          (->> (streams/position-stream points)
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
