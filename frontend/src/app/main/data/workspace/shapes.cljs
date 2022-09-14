;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.proportions :as gpr]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.page :as csp]
   [app.common.types.shape :as spec.shape]
   [app.common.types.shape.interactions :as csi]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dc]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.streams :as ms]
   [app.util.names :as un]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(s/def ::shape-attrs ::spec.shape/shape-attrs)

(defn get-shape-layer-position
  [objects selected attrs]

  ;; Calculate the frame over which we're drawing
  (let [position @ms/mouse-position
        frame-id (:frame-id attrs (cph/top-nested-frame objects position))
        shape (when-not (empty? selected)
                (cph/get-base-shape objects selected))]

    ;; When no shapes has been selected or we're over a different frame
    ;; we add it as the latest shape of that frame
    (if (or (not shape) (not= (:frame-id shape) frame-id))
      [frame-id frame-id nil]

      ;; Otherwise, we add it to next to the selected shape
      (let [index (cph/get-position-on-parent objects (:id shape))
            {:keys [frame-id parent-id]} shape]
        [frame-id parent-id (inc index)]))))

(defn make-new-shape
  [attrs objects selected]
  (let [default-attrs (if (= :frame (:type attrs))
                        cp/default-frame-attrs
                        cp/default-shape-attrs)

        selected-non-frames
        (into #{} (comp (map (d/getf objects))
                        (remove cph/frame-shape?))
              selected)

        [frame-id parent-id index]
        (get-shape-layer-position objects selected-non-frames attrs)]

    (-> (merge default-attrs attrs)
        (gpr/setup-proportions)
        (assoc :frame-id frame-id
               :parent-id parent-id
               :index index))))

(defn add-shape
  ([attrs]
   (add-shape attrs {}))

  ([attrs {:keys [no-select?]}]
   (us/verify ::shape-attrs attrs)
   (ptk/reify ::add-shape
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id  (:current-page-id state)
             objects  (wsh/lookup-page-objects state page-id)
             selected (wsh/lookup-selected state)

             id       (or (:id attrs) (uuid/next))
             name     (-> objects
                          (un/retrieve-used-names)
                          (un/generate-unique-name (:name attrs)))

             shape (make-new-shape
                     (assoc attrs :id id :name name)
                     objects
                     selected)

             changes  (-> (pcb/empty-changes it page-id)
                          (pcb/with-objects objects)
                          (pcb/add-object shape)
                          (cond-> (some? (:parent-id attrs))
                            (pcb/change-parent (:parent-id attrs) [shape])))]

         (rx/concat
          (rx/of (dch/commit-changes changes)
                 (when-not no-select?
                   (dws/select-shapes (d/ordered-set id))))
          (when (= :text (:type attrs))
            (->> (rx/of (dwe/start-edition-mode id))
                 (rx/observe-on :async)))))))))

(defn move-shapes-into-frame [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)

            to-move-shapes
            (into []
                  (map (d/getf objects))
                  (reverse (cph/sort-z-index objects shapes)))

            changes
            (when (d/not-empty? to-move-shapes)
              (-> (pcb/empty-changes it page-id)
                  (pcb/with-objects objects)
                  (pcb/change-parent frame-id to-move-shapes 0)))]

        (if (some? changes)
          (rx/of (dch/commit-changes changes))
          (rx/empty))))))

(s/def ::set-of-uuid
  (s/every ::us/uuid :kind set?))

(defn delete-shapes
  [ids]
  (us/assert ::set-of-uuid ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            page    (wsh/lookup-page state page-id)

            ids     (cph/clean-loops objects ids)
            lookup  (d/getf objects)

            groups-to-unmask
            (reduce (fn [group-ids id]
                      ;; When the shape to delete is the mask of a masked group,
                      ;; the mask condition must be removed, and it must be
                      ;; converted to a normal group.
                      (let [obj    (lookup id)
                            parent (lookup (:parent-id obj))]
                        (if (and (:masked-group? parent)
                                 (= id (first (:shapes parent))))
                          (conj group-ids (:id parent))
                          group-ids)))
                    #{}
                    ids)

            interacting-shapes
            (filter (fn [shape]
                      ;; If any of the deleted shapes is the destination of
                      ;; some interaction, this must be deleted, too.
                      (let [interactions (:interactions shape)]
                        (some #(and (csi/has-destination %)
                                    (contains? ids (:destination %)))
                              interactions)))
                    (vals objects))

            ;; If any of the deleted shapes is a frame with guides
            guides (into {}
                         (comp (map second)
                               (remove #(contains? ids (:frame-id %)))
                               (map (juxt :id identity)))
                         (dm/get-in page [:options :guides]))

            starting-flows
            (filter (fn [flow]
                      ;; If any of the deleted is a frame that starts a flow,
                      ;; this must be deleted, too.
                      (contains? ids (:starting-frame flow)))
                    (-> page :options :flows))

            all-parents
            (reduce (fn [res id]
                      ;; All parents of any deleted shape must be resized.
                      (into res (cph/get-parent-ids objects id)))
                    (d/ordered-set)
                    ids)

            all-children
            (->> ids ;; Children of deleted shapes must be also deleted.
                 (reduce (fn [res id]
                           (into res (cph/get-children-ids objects id)))
                         [])
                 (reverse)
                 (into (d/ordered-set)))

            find-all-empty-parents
            (fn recursive-find-empty-parents [empty-parents]
              (let [all-ids   (into empty-parents ids)
                    contains? (partial contains? all-ids)
                    xform     (comp (map lookup)
                                    (filter cph/group-shape?)
                                    (remove #(->> (:shapes %) (remove contains?) seq))
                                    (map :id))
                    parents   (into #{} xform all-parents)]
                (if (= empty-parents parents)
                  empty-parents
                  (recursive-find-empty-parents parents))))

            empty-parents
            ;; Any parent whose children are all deleted, must be deleted too.
            (into (d/ordered-set) (find-all-empty-parents #{}))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-page page)
                        (pcb/with-objects objects)
                        (pcb/set-page-option :guides guides)
                        (pcb/remove-objects all-children)
                        (pcb/remove-objects ids)
                        (pcb/remove-objects empty-parents)
                        (pcb/resize-parents all-parents)
                        (pcb/update-shapes groups-to-unmask
                                           (fn [shape]
                                             (assoc shape :masked-group? false)))
                        (pcb/update-shapes (map :id interacting-shapes)
                                           (fn [shape]
                                             (d/update-when shape :interactions
                                                            (fn [interactions]
                                                              (into []
                                                                    (remove #(and (csi/has-destination %)
                                                                                  (contains? ids (:destination %))))
                                                                    interactions)))))
                        (cond-> (seq starting-flows)
                          (pcb/update-page-option :flows (fn [flows]
                                                           (->> (map :id starting-flows)
                                                                (reduce csp/remove-flow flows))))))]

        (rx/of
         (dc/detach-comment-thread ids)
         (dch/commit-changes changes))))))

(defn- viewport-center
  [state]
  (let [{:keys [x y width height]} (get-in state [:workspace-local :vbox])]
    [(+ x (/ width 2)) (+ y (/ height 2))]))

(defn create-and-add-shape
  [type frame-x frame-y data]
  (ptk/reify ::create-and-add-shape
    ptk/WatchEvent
    (watch [_ state _]
      (let [{:keys [width height]} data

            [vbc-x vbc-y] (viewport-center state)
            x (:x data (- vbc-x (/ width 2)))
            y (:y data (- vbc-y (/ height 2)))
            page-id (:current-page-id state)
            frame-id (-> (wsh/lookup-page-objects state page-id)
                         (cph/top-nested-frame {:x frame-x :y frame-y}))
            shape (-> (cp/make-minimal-shape type)
                      (merge data)
                      (merge {:x x :y y})
                      (assoc :frame-id frame-id)
                      (cp/setup-rect-selrect))]
        (rx/of (add-shape shape))))))
