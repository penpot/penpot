;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.proportions :as gpr]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dc]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.features :as features]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(s/def ::shape-attrs ::cts/shape-attrs)

(defn get-shape-layer-position
  [objects selected attrs]

  ;; Calculate the frame over which we're drawing
  (let [position @ms/mouse-position
        frame-id (:frame-id attrs (ctst/top-nested-frame objects position))
        shape    (when-not (empty? selected)
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
                        cts/default-frame-attrs
                        cts/default-shape-attrs)

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
                          (ctst/retrieve-used-names)
                          (ctst/generate-unique-name (:name attrs)))

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
                 (dwsl/update-layout-positions [(:parent-id shape)])
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
                  (reverse (ctst/sort-z-index objects shapes)))

            changes
            (when (d/not-empty? to-move-shapes)
              (-> (pcb/empty-changes it page-id)
                  (pcb/with-objects objects)
                  (pcb/change-parent frame-id to-move-shapes 0)))]

        (if (some? changes)
          (rx/of (dch/commit-changes changes))
          (rx/empty))))))

(defn delete-shapes
  ([ids] (delete-shapes nil ids))
  ([page-id ids]
   (us/assert ::us/set-of-uuid ids)
   (ptk/reify ::delete-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [file-id   (:current-file-id state)
             page-id   (or page-id (:current-page-id state))
             file      (wsh/get-file state file-id)
             page      (wsh/lookup-page state page-id)
             objects   (wsh/lookup-page-objects state page-id)

             ids     (cph/clean-loops objects ids)
             lookup  (d/getf objects)

             components-v2 (features/active-feature? state :components-v2)

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
                         (some #(and (ctsi/has-destination %)
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

             components-to-delete
             (if components-v2
               (reduce (fn [components id]
                         (let [shape (get objects id)]
                           (if (and (= (:component-file shape) file-id) ;; Main instances should exist only in local file
                                    (:main-instance? shape))            ;; but check anyway
                             (conj components (:component-id shape))
                             components)))
                       []
                       (into ids all-children))
               [])

             changes (-> (pcb/empty-changes it page-id)
                         (pcb/with-page page)
                         (pcb/with-objects objects)
                         (pcb/with-library-data file)
                         (pcb/set-page-option :guides guides))

             changes (reduce (fn [changes component-id]
                               ;; It's important to delete the component before the main instance, because we
                               ;; need to store the instance position if we want to restore it later.
                               (pcb/delete-component changes component-id components-v2))
                             changes
                             components-to-delete)

             changes (-> changes
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
                                                                     (remove #(and (ctsi/has-destination %)
                                                                                   (contains? ids (:destination %))))
                                                                     interactions)))))
                         (cond-> (seq starting-flows)
                           (pcb/update-page-option :flows (fn [flows]
                                                            (->> (map :id starting-flows)
                                                                 (reduce ctp/remove-flow flows))))))]

         (rx/of (dc/detach-comment-thread ids)
                (dwsl/update-layout-positions all-parents)
                (dch/commit-changes changes)))))))

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
                         (ctst/top-nested-frame {:x frame-x :y frame-y}))
            shape (-> (cts/make-minimal-shape type)
                      (merge data)
                      (merge {:x x :y y})
                      (assoc :frame-id frame-id)
                      (cts/setup-rect-selrect))]
        (rx/of (add-shape shape))))))
