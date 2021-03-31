;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.selection
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.path.state :as st]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn path-pointer-enter [position]
  (ptk/reify ::path-pointer-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-points] (fnil conj #{}) position)))))

(defn path-pointer-leave [position]
  (ptk/reify ::path-pointer-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-points] disj position)))))

(defn select-handler [index type]
  (ptk/reify ::select-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected-handlers] (fnil conj #{}) [index type]))))))


(defn path-handler-enter [index prefix]
  (ptk/reify ::path-handler-enter
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-handlers] (fnil conj #{}) [index prefix])))))

(defn path-handler-leave [index prefix]
  (ptk/reify ::path-handler-leave
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :hover-handlers] disj [index prefix])))))

(defn select-node-area [shift?]
  (ptk/reify ::select-node-area
    ptk/UpdateEvent
    (update [_ state]
      (let [selrect (get-in state [:workspace-local :selrect])
            id (get-in state [:workspace-local :edition])
            content (get-in state (st/get-path state :content))
            selected-point? (fn [point]
                              (gsh/has-point-rect? selrect point))
            positions (into #{}
                            (comp (map (comp gpt/point :params))
                                  (filter selected-point?))
                            content)]
        (cond-> state
          (some? id)
          (assoc-in [:workspace-local :edit-path id :selected-points] positions))))))

(defn select-node [position shift?]
  (ptk/reify ::select-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (cond-> state
          (some? id)
          (assoc-in [:workspace-local :edit-path id :selected-points] #{position}))))))

(defn deselect-node [position shift?]
  (ptk/reify ::deselect-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected-points] (fnil disj #{}) position))))))

(defn add-to-selection-handler [index type]
  (ptk/reify ::add-to-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn add-to-selection-node [index]
  (ptk/reify ::add-to-selection-node
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn remove-from-selection-handler [index]
  (ptk/reify ::remove-from-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn remove-from-selection-node [index]
  (ptk/reify ::remove-from-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :selected-handlers] #{})
            (assoc-in [:workspace-local :edit-path id :selected-points] #{}))))))

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

(defn handle-selection
  [shift?]
  (letfn [(valid-rect? [{width :width height :height}]
            (or (> width 10) (> height 10)))]

    (ptk/reify ::handle-selection
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stop? (fn [event] (or (dwc/interrupt? event) (ms/mouse-up? event)))
              stoper (->> stream (rx/filter stop?))
              from-p @ms/mouse-position]
          (rx/concat
           (->> ms/mouse-position
                (rx/take-until stoper)
                (rx/map #(gsh/points->rect [from-p %]))
                (rx/filter valid-rect?)
                (rx/map update-area-selection))

           (rx/of (select-node-area shift?)
                  (clear-area-selection))))))))
