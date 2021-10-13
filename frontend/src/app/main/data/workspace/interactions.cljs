;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.interactions
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.interactions :as cti]
   [app.common.types.page-options :as cto]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; --- Flows

(defn add-flow
  [starting-frame]
  (ptk/reify ::add-flow
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            flows   (get-in state [:workspace-data
                                   :pages-index
                                   page-id
                                   :options
                                   :flows] [])

            unames  (into #{} (map :name flows))
            name    (dwc/generate-unique-name unames "Flow-1")

            new-flow {:id (uuid/next)
                      :name name
                      :starting-frame starting-frame}]

        (rx/of (dch/commit-changes
                {:redo-changes [{:type :set-option
                                 :page-id page-id
                                 :option :flows
                                 :value (cto/add-flow flows new-flow)}]
                 :undo-changes [{:type :set-option
                                 :page-id page-id
                                 :option :flows
                                 :value flows}]
                 :origin it}))))))

(defn add-flow-selected-frame
  []
  (ptk/reify ::add-flow-selected-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (add-flow (first selected)))))))

(defn remove-flow
  [flow-id]
  (us/verify ::us/uuid flow-id)
  (ptk/reify ::remove-flow
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            flows   (get-in state [:workspace-data
                                   :pages-index
                                   page-id
                                   :options
                                   :flows] [])]
        (rx/of (dch/commit-changes
                {:redo-changes [{:type :set-option
                                 :page-id page-id
                                 :option :flows
                                 :value (cto/remove-flow flows flow-id)}]
                 :undo-changes [{:type :set-option
                                 :page-id page-id
                                 :option :flows
                                 :value flows}]
                 :origin it}))))))

(defn rename-flow
  [flow-id name]
  (us/verify ::us/uuid flow-id)
  (us/verify ::us/string name)
  (ptk/reify ::rename-flow
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            flows   (get-in state [:workspace-data
                                   :pages-index
                                   page-id
                                   :options
                                   :flows] [])]
        (rx/of (dch/commit-changes
                {:redo-changes [{:type :set-option
                                 :page-id page-id
                                 :option :flows
                                 :value (cto/update-flow flows flow-id
                                                         #(cto/rename-flow % name))}]
                 :undo-changes [{:type :set-option
                                 :page-id page-id
                                 :option :flows
                                 :value flows}]
                 :origin it}))))))


(defn start-rename-flow
  [id]
  (us/verify ::us/uuid id)
  (ptk/reify ::start-rename-flow
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :flow-for-rename] id))))

(defn end-rename-flow
  []
  (ptk/reify ::end-rename-flow
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :flow-for-rename))))

;; --- Interactions

(defn add-new-interaction
  ([shape] (add-new-interaction shape nil))
  ([shape destination]
   (ptk/reify ::add-new-interaction
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id  (:current-page-id state)
             objects  (wsh/lookup-page-objects state page-id)
             frame    (cph/get-frame shape objects)
             flows    (get-in state [:workspace-data
                                     :pages-index
                                     page-id
                                     :options
                                     :flows] [])
             flow     (cto/get-frame-flow flows (:id frame))]
         (rx/concat
           (rx/of (dch/update-shapes [(:id shape)]
                    (fn [shape]
                      (let [new-interaction (cti/set-destination
                                              cti/default-interaction
                                              destination)]
                        (update shape :interactions
                                cti/add-interaction new-interaction)))))
           (when (and (not (cph/connected-frame? (:id frame) objects))
                      (nil? flow))
             (rx/of (add-flow (:id frame))))))))))

(defn remove-interaction
  [shape index]
  (ptk/reify ::remove-interaction
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [(:id shape)]
               (fn [shape]
                 (update shape :interactions
                         cti/remove-interaction index)))))))

(defn update-interaction
  [shape index update-fn]
  (ptk/reify ::update-interaction
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [(:id shape)]
               (fn [shape]
                 (update shape :interactions
                        cti/update-interaction index update-fn)))))))

(declare move-edit-interaction)
(declare finish-edit-interaction)

(defn start-edit-interaction
  [index]
  (ptk/reify ::start-edit-interaction
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :editing-interaction-index] index))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial-pos @ms/mouse-position
            selected (wsh/lookup-selected state)
            stopper (rx/filter ms/mouse-up? stream)]
        (when (= 1 (count selected))
          (rx/concat
            (->> ms/mouse-position
                 (rx/take-until stopper)
                 (rx/map #(move-edit-interaction initial-pos %)))
            (rx/of (finish-edit-interaction index initial-pos))))))))

(defn move-edit-interaction
  [initial-pos position]
  (ptk/reify ::move-edit-interaction
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            selected-shape-id (-> state wsh/lookup-selected first)
            selected-shape (get objects selected-shape-id)
            selected-shape-frame-id (:frame-id selected-shape)
            start-frame (get objects selected-shape-frame-id)
            end-frame   (dwc/get-frame-at-point objects position)]
        (cond-> state
          (not= position initial-pos) (assoc-in [:workspace-local :draw-interaction-to] position)
          (not= start-frame end-frame) (assoc-in [:workspace-local :draw-interaction-to-frame] end-frame))))))

(defn finish-edit-interaction
  [index initial-pos]
  (ptk/reify ::finish-edit-interaction
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :editing-interaction-index] nil)
          (assoc-in [:workspace-local :draw-interaction-to] nil)
          (assoc-in [:workspace-local :draw-interaction-to-frame] nil)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [position @ms/mouse-position
            page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)
            frame    (dwc/get-frame-at-point objects position)

            shape-id (-> state wsh/lookup-selected first)
            shape    (get objects shape-id)]

        (when (and shape (not (= position initial-pos)))
          (if (nil? frame)
            (when index
              (rx/of (remove-interaction shape index)))
            (let [frame (if (or (= (:id frame) (:id shape))
                                (= (:id frame) (:frame-id shape)))
                          nil ;; Drop onto self frame -> set destination to none
                          frame)]
              (if (nil? index)
                (rx/of (add-new-interaction shape (:id frame)))
                (rx/of (update-interaction shape index
                                           (fn [interaction]
                                             (cond-> interaction
                                               (not (cti/has-destination interaction))
                                               (cti/set-action-type :navigate)

                                               :always
                                               (cti/set-destination (:id frame))))))))))))))
;; --- Overlays

(declare move-overlay-pos)
(declare finish-move-overlay-pos)

(defn start-move-overlay-pos
  [index]
  (ptk/reify ::start-move-overlay-pos
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :move-overlay-to] nil)
          (assoc-in [:workspace-local :move-overlay-index] index)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial-pos @ms/mouse-position
            selected (wsh/lookup-selected state)
            stopper (rx/filter ms/mouse-up? stream)]
        (when (= 1 (count selected))
          (let [page-id     (:current-page-id state)
                objects     (wsh/lookup-page-objects state page-id)
                shape       (->> state
                                 wsh/lookup-selected
                                 first
                                 (get objects))
                overlay-pos (-> shape
                                (get-in [:interactions index])
                                :overlay-position)
                orig-frame  (cph/get-frame shape objects)
                frame-pos   (gpt/point (:x orig-frame) (:y orig-frame))
                offset      (-> initial-pos
                                (gpt/subtract overlay-pos)
                                (gpt/subtract frame-pos))]
            (rx/concat
              (->> ms/mouse-position
                   (rx/take-until stopper)
                   (rx/map #(move-overlay-pos % frame-pos offset)))
              (rx/of (finish-move-overlay-pos index frame-pos offset)))))))))

(defn move-overlay-pos
  [pos frame-pos offset]
  (ptk/reify ::move-overlay-pos
    ptk/UpdateEvent
    (update [_ state]
      (let [pos (-> pos
                    (gpt/subtract frame-pos)
                    (gpt/subtract offset))]
        (assoc-in state [:workspace-local :move-overlay-to] pos)))))

(defn finish-move-overlay-pos
 [index frame-pos offset]
 (ptk/reify ::finish-move-overlay-pos
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/dissoc-in [:workspace-local :move-overlay-to])
          (d/dissoc-in [:workspace-local :move-overlay-index])))

   ptk/WatchEvent
   (watch [_ state _]
     (let [pos         @ms/mouse-position
           overlay-pos (-> pos
                           (gpt/subtract frame-pos)
                           (gpt/subtract offset))

           page-id     (:current-page-id state)
           objects     (wsh/lookup-page-objects state page-id)
           shape       (->> state
                            wsh/lookup-selected
                            first
                            (get objects))

           interactions (:interactions shape)

           new-interactions
           (update interactions index
                   #(cti/set-overlay-position % overlay-pos))]

       (rx/of (dch/update-shapes [(:id shape)] #(merge % {:interactions new-interactions})))))))

