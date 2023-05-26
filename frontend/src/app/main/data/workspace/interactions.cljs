;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.interactions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.types.page :as ctp]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; --- Flows

(defn add-flow
  [starting-frame]
  (ptk/reify ::add-flow
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (wsh/lookup-page state)

            flows   (get-in page [:options :flows] [])
            unames  (cfh/get-used-names flows)
            name    (cfh/generate-unique-name unames "Flow 1")

            new-flow {:id (uuid/next)
                      :name name
                      :starting-frame starting-frame}]

        (rx/of (dch/commit-changes
                 (-> (pcb/empty-changes it)
                     (pcb/with-page page)
                     (pcb/update-page-option :flows ctp/add-flow new-flow))))))))

(defn add-flow-selected-frame
  []
  (ptk/reify ::add-flow-selected-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (wsh/lookup-selected state)]
        (rx/of (add-flow (first selected)))))))

(defn remove-flow
  [flow-id]
  (dm/assert! (uuid? flow-id))
  (ptk/reify ::remove-flow
    ptk/WatchEvent
    (watch [it state _]
      (let [page (wsh/lookup-page state)]
        (rx/of (dch/commit-changes
                 (-> (pcb/empty-changes it)
                     (pcb/with-page page)
                     (pcb/update-page-option :flows ctp/remove-flow flow-id))))))))

(defn rename-flow
  [flow-id name]
  (dm/assert! (uuid? flow-id))
  (dm/assert! (string? name))
  (ptk/reify ::rename-flow
    ptk/WatchEvent
    (watch [it state _]
      (let [page (wsh/lookup-page state) ]
        (rx/of (dch/commit-changes
                 (-> (pcb/empty-changes it)
                     (pcb/with-page page)
                     (pcb/update-page-option :flows ctp/update-flow flow-id
                                             #(ctp/rename-flow % name)))))))))

(defn start-rename-flow
  [id]
  (dm/assert! (uuid? id))
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

(defn- connected-frame?
  "Check if some frame is origin or destination of any navigate interaction
  in the page"
  [objects frame-id]
  (let [children (cph/get-children-with-self objects frame-id)]
    (or (some ctsi/flow-origin? (map :interactions children))
        (some #(ctsi/flow-to? % frame-id) (map :interactions (vals objects))))))

(defn add-new-interaction
  ([shape] (add-new-interaction shape nil))
  ([shape destination]
   (ptk/reify ::add-new-interaction
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id  (:current-page-id state)
             objects  (wsh/lookup-page-objects state page-id)
             frame    (cph/get-root-frame objects (:id shape))
             flows    (get-in state [:workspace-data
                                     :pages-index
                                     page-id
                                     :options
                                     :flows] [])
             flow     (ctp/get-frame-flow flows (:id frame))]
         (rx/concat
           (rx/of (dch/update-shapes [(:id shape)]
                    (fn [shape]
                      (let [new-interaction (-> ctsi/default-interaction
                                                (ctsi/set-destination destination)
                                                (assoc :position-relative-to (:id shape)))]
                        (update shape :interactions
                                ctsi/add-interaction new-interaction)))))
           (when (and (not (connected-frame? objects (:id frame)))
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
                         ctsi/remove-interaction index)))))))

(defn update-interaction
  [shape index update-fn]
  (ptk/reify ::update-interaction
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [(:id shape)]
               (fn [shape]
                 (update shape :interactions
                        ctsi/update-interaction index update-fn)))))))

(defn remove-all-interactions-nav-to
  "Remove all interactions that navigate to the given frame."
  [frame-id]
  (ptk/reify ::remove-all-interactions-nav-to
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)

            remove-interactions-shape
            (fn [shape]
              (let [interactions     (:interactions shape)
                    new-interactions (ctsi/remove-interactions #(ctsi/navs-to? % frame-id)
                                                               interactions)]
                (when (not= (count interactions) (count new-interactions))
                  (dch/update-shapes [(:id shape)]
                                     (fn [shape]
                                       (assoc shape :interactions new-interactions))))))]

        (rx/from (->> (vals objects)
                      (map remove-interactions-shape)
                      (d/vec-without-nils)))))))

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

(defn- get-target-frame
  [state position]

  (let [objects (wsh/lookup-page-objects state)
        from-id (-> state wsh/lookup-selected first)
        from-shape (wsh/lookup-shape state from-id)

        from-frame-id (if (cph/frame-shape? from-shape)
                        from-id (:frame-id from-shape))

        target-frame (ctst/frame-by-position objects position)]

    (when (and (not= (:id target-frame) uuid/zero)
               (not= (:id target-frame) from-frame-id))
      target-frame)))

(defn move-edit-interaction
  [_initial-pos position]
  (ptk/reify ::move-edit-interaction
    ptk/UpdateEvent
    (update [_ state]
      (let [end-frame (get-target-frame state position)]
        (-> state
            (assoc-in [:workspace-local :draw-interaction-to] position)
            (assoc-in [:workspace-local :draw-interaction-to-frame] end-frame))))))

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
      (let [position     @ms/mouse-position
            target-frame (get-target-frame state position)
            shape-id     (-> state wsh/lookup-selected first)
            shape        (wsh/lookup-shape state shape-id)

            change-interaction
            (fn [interaction]
              (cond-> interaction
                (not (ctsi/has-destination interaction))
                (ctsi/set-action-type :navigate)

                :always
                (ctsi/set-destination (:id target-frame))))
            undo-id (js/Symbol)]

        (rx/of
          (dwu/start-undo-transaction undo-id)

          (when (:hide-in-viewer target-frame)
            ; If the target frame is hidden, we need to unhide it so
            ; users can navigate to it.
            (dch/update-shapes [(:id target-frame)]
                               #(dissoc % :hide-in-viewer)))

          (cond
            (or (nil? shape)
                ;; Didn't changed the position for the interaction
                (= position initial-pos)
                ;; New interaction but invalid target
                (and (nil? index) (nil? target-frame)))
            nil

            ;; Dropped interaction in an invalid target. We remove it
            (and (some? index) (nil? target-frame))
            (remove-interaction shape index)

            (nil? index)
            (add-new-interaction shape (:id target-frame))

            :else
            (update-interaction shape index change-interaction))

          (dwu/commit-undo-transaction undo-id))))))

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
                orig-frame  (cph/get-frame objects shape)
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
                   #(ctsi/set-overlay-position % overlay-pos))]

       (rx/of (dch/update-shapes [(:id shape)] #(merge % {:interactions new-interactions})))))))

