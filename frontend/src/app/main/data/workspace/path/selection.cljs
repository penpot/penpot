;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.selection
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.rect :as grc]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [beicon.v2.operators :as rxo]
   [potok.v2.core :as ptk]))

(defn path-pointer-enter [index]
  (ptk/reify ::path-pointer-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover :nodes] (fnil conj #{}) index)))))

(defn path-pointer-leave [index]
  (ptk/reify ::path-pointer-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover :nodes] disj index)))))

(defn path-handler-enter [index prefix]
  (ptk/reify ::path-handler-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover :handlers] (fnil conj #{}) [index prefix])))))

(defn path-handler-leave [index prefix]
  (ptk/reify ::path-handler-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover :handlers] disj [index prefix])))))

(defn path-segment-enter [index]
  (ptk/reify ::path-segment-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover :segments] (fnil conj #{}) index)))))

(defn path-segment-leave [index]
  (ptk/reify ::path-segment-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover :segments] disj index)))))

(defn- select-element
  [state type identity shift?]
  (let [id        (dm/get-in state [:workspace-local :edition])
        selection (or (st/get-selection state id) helpers/empty-selection)
        selected  (get selection type #{})
        selection (cond
                    (and shift? (contains? selected identity))
                    (update selection type disj identity)

                    shift?
                    (update selection type (fnil conj #{}) identity)

                    :else
                    (assoc helpers/empty-selection type #{identity}))]
    (cond-> state
      (some? id)
      (assoc-in [:workspace-local :edit-path id :selection] selection))))

(defn select-node [index shift?]
  (ptk/reify ::select-node
    ptk/UpdateEvent
    (update [_ state]
      (select-element state :nodes index shift?))))

(defn select-segment [index shift?]
  (ptk/reify ::select-segment
    ptk/UpdateEvent
    (update [_ state]
      (select-element state :segments index shift?))))

(defn select-handler [index prefix shift?]
  (ptk/reify ::select-handler
    ptk/UpdateEvent
    (update [_ state]
      (select-element state :handlers [index prefix] shift?))))

(defn- update-area-set
  [initial-set in-rect remove?]
  (if remove?
    (apply disj initial-set in-rect)
    (into initial-set in-rect)))

(defn select-path-area
  [rect initial-selection remove?]
  (ptk/reify ::select-path-area
    ptk/UpdateEvent
    (update [_ state]
      (if-not (grc/rect? rect)
        state
        (let [id       (dm/get-in state [:workspace-local :edition])
              content  (st/get-path state :content)

              ;; Marquee priority is nodes, segments, then handlers.
              nodes    (helpers/nodes-in-rect content rect)
              segments (if (empty? nodes)
                         (helpers/segments-in-rect content rect)
                         #{})
              handlers (if (and (empty? nodes) (empty? segments))
                         (helpers/handlers-in-rect content rect)
                         #{})
              in-rect  {:nodes nodes
                        :segments segments
                        :handlers handlers}
              selection
              (reduce-kv
               (fn [selection type identities]
                 (assoc selection type
                        (update-area-set (get initial-selection type #{})
                                         identities
                                         remove?)))
               helpers/empty-selection
               in-rect)]
          (cond-> state
            (some? id)
            (assoc-in [:workspace-local :edit-path id :selection] selection)))))))

(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :selection] helpers/empty-selection)))))

(defn select-all-nodes []
  (ptk/reify ::select-all-nodes
    ptk/UpdateEvent
    (update [_ state]
      (let [id        (st/get-path-id state)
            content   (st/get-path state :content)
            selection (assoc helpers/empty-selection
                             :nodes (into #{} (helpers/node-indices content)))]
        (assoc-in state [:workspace-local :edit-path id :selection] selection)))))

(defn update-area-selection
  [rect]
  (ptk/reify ::update-area-selection
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selrect] rect))))

(defn clear-area-selection
  []
  (ptk/reify ::clear-area-selection
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :selrect))))

(defn handle-area-selection
  [append? remove?]
  (letfn [(valid-rect? [zoom {width :width height :height}]
            (or (> width (/ 10 zoom)) (> height (/ 10 zoom))))]

    (ptk/reify ::handle-area-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [id      (dm/get-in state [:workspace-local :edition])
              zoom    (dm/get-in state [:workspace-local :zoom] 1)
              stopper (mse/drag-stopper stream)
              from-p  @ms/mouse-position

              initial-selection
              (if (or append? remove?)
                (or (st/get-selection state id) helpers/empty-selection)
                helpers/empty-selection)

              selrect-stream
              (->> ms/mouse-position
                   (rx/map #(grc/points->rect [from-p %]))
                   (rx/filter (partial valid-rect? zoom))
                   (rx/take-until stopper))]

          (rx/concat
           (if (or append? remove?)
             (rx/empty)
             (rx/of (deselect-all)))
           (rx/merge
            (->> selrect-stream
                 (rx/map update-area-selection))
            ;; Limit path hit-testing to once per animation frame.
            (->> selrect-stream
                 (rx/buffer-time 16)
                 (rx/map last)
                 (rx/filter some?)
                 (rx/pipe (rxo/distinct-contiguous))
                 (rx/map #(select-path-area % initial-selection remove?))))
           (rx/of (clear-area-selection))))))))
