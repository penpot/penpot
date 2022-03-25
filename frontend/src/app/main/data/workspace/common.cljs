;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.common
  (:require
   [app.common.data :as d]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.spec.interactions :as csi]
   [app.common.spec.page :as csp]
   [app.common.spec.shape :as spec.shape]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(s/def ::shape-attrs ::spec.shape/shape-attrs)
(s/def ::set-of-string (s/every string? :kind set?))
(s/def ::ordered-set-of-uuid (s/every uuid? :kind d/ordered-set?))

(defn initialized?
  "Check if the state is properly intialized in a workspace. This means
  it has the `:current-page-id` and `:current-file-id` properly set."
  [state]
  (and (uuid? (:current-file-id state))
       (uuid? (:current-page-id state))))

;; --- Helpers

(defn interrupt? [e] (= e :interrupt))

;; --- Selection Index Handling

(defn initialize-indices
  [{:keys [file] :as bundle}]
  (ptk/reify ::setup-selection-index
    ptk/WatchEvent
    (watch [_ _ _]
      (let [msg {:cmd :initialize-indices
                 :file-id (:id file)
                 :data (:data file)}]
        (->> (uw/ask! msg)
             (rx/map (constantly ::index-initialized)))))))

;; --- Common Helpers & Events

;; TODO: looks duplicate

(defn get-frame-at-point
  [objects point]
  (let [frames (cph/get-frames objects)]
    (d/seek #(gsh/has-point? % point) frames)))

(defn- extract-numeric-suffix
  [basename]
  (if-let [[_ p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn retrieve-used-names
  [objects]
  (into #{} (comp (map :name) (remove nil?)) (vals objects)))


(defn generate-unique-name
  "A unique name generator"
  [used basename]
  (s/assert ::set-of-string used)
  (s/assert ::us/string basename)
  (if-not (contains? used basename)
    basename
    (let [[prefix initial] (extract-numeric-suffix basename)]
      (loop [counter initial]
        (let [candidate (str prefix "-" counter)]
          (if (contains? used candidate)
            (recur (inc counter))
            candidate))))))

;; --- Shape attrs (Layers Sidebar)

(defn toggle-collapse
  [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :expanded id] not))))

(defn expand-collapse
  [id]
  (ptk/reify ::expand-collapse
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :expanded id] true))))

(def collapse-all
  (ptk/reify ::collapse-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :expanded))))


;; These functions should've been in `src/app/main/data/workspace/undo.cljs` but doing that causes
;; a circular dependency with `src/app/main/data/workspace/changes.cljs`
(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        ;; Editors handle their own undo's
        (when-not (or (some? edition) (not-empty drawing))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index -1))
              (let [changes (get-in items [index :undo-changes])]
                (rx/of (dwu/materialize-undo changes (dec index))
                       (dch/commit-changes {:redo-changes changes
                                            :undo-changes []
                                            :save-undo? false
                                            :origin it}))))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (or (some? edition) (not-empty drawing))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index (dec (count items))))
              (let [changes (get-in items [(inc index) :redo-changes])]
                (rx/of (dwu/materialize-undo changes (inc index))
                       (dch/commit-changes {:redo-changes changes
                                            :undo-changes []
                                            :origin it
                                            :save-undo? false}))))))))))

(defn undo-to-index
  "Repeat undoing or redoing until dest-index is reached."
  [dest-index]
  (ptk/reify ::undo-to-index
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (or (some? edition) (not-empty drawing))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when (and (some? items)
                       (<= 0 dest-index (dec (count items))))
              (let [changes (vec (apply concat
                                        (cond
                                          (< dest-index index)
                                          (->> (subvec items (inc dest-index) (inc index))
                                               (reverse)
                                               (map :undo-changes))
                                          (> dest-index index)
                                          (->> (subvec items (inc index) (inc dest-index))
                                               (map :redo-changes))
                                          :else [])))]
                (when (seq changes)
                  (rx/of (dwu/materialize-undo changes dest-index)
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :origin it
                                              :save-undo? false})))))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expand-all-parents
  [ids objects]
  (ptk/reify ::expand-all-parents
    ptk/UpdateEvent
    (update [_ state]
      (let [expand-fn (fn [expanded]
                        (merge expanded
                          (->> ids
                               (map #(cph/get-parent-ids objects %))
                               flatten
                               (remove #(= % uuid/zero))
                               (map (fn [id] {id true}))
                               (into {}))))]
        (update-in state [:workspace-local :expanded] expand-fn)))))

;; --- Update Shape Attrs

;; NOTE: This is a generic implementation for update multiple shapes
;; in one single commit/undo entry.


(defn select-shapes
  [ids]
  (us/verify ::ordered-set-of-uuid ids)
  (ptk/reify ::select-shapes
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :selected] ids))

    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)]
        (rx/of (expand-all-parents ids objects))))))

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (wsh/lookup-page-objects state)]
        ;; Can only edit objects that exist
        (if (contains? objects id)
          (-> state
              (assoc-in [:workspace-local :selected] #{id})
              (assoc-in [:workspace-local :edition] id))
          state)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter interrupt?)
           (rx/take 1)
           (rx/map (constantly clear-edition-mode))))))

;; If these event change modules review /src/app/main/data/workspace/path/undo.cljs
(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update :workspace-local dissoc :edition)
            (cond-> (some? id) (update-in [:workspace-local :edit-path] dissoc id)))))))

(defn get-shape-layer-position
  [objects selected attrs]

  (if (= :frame (:type attrs))
    ;; Frames are always positioned on the root frame
    [uuid/zero uuid/zero nil]

    ;; Calculate the frame over which we're drawing
    (let [position @ms/mouse-position
          frame-id (:frame-id attrs (cph/frame-id-by-position objects position))
          shape (when-not (empty? selected)
                  (cph/get-base-shape objects selected))]

      ;; When no shapes has been selected or we're over a different frame
      ;; we add it as the latest shape of that frame
      (if (or (not shape) (not= (:frame-id shape) frame-id))
        [frame-id frame-id nil]

        ;; Otherwise, we add it to next to the selected shape
        (let [index (cph/get-position-on-parent objects (:id shape))
              {:keys [frame-id parent-id]} shape]
          [frame-id parent-id (inc index)])))))

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
                          (retrieve-used-names)
                          (generate-unique-name (:name attrs)))

             shape (make-new-shape
                     (assoc attrs :id id :name name)
                     objects
                     selected)

             changes  (-> (pcb/empty-changes it page-id)
                          (pcb/add-object shape {:index (when (= :frame (:type shape)) 0)}))]

         (rx/concat
          (rx/of (dch/commit-changes changes)
                 (when-not no-select?
                   (select-shapes (d/ordered-set id))))
          (when (= :text (:type attrs))
            (->> (rx/of (start-edition-mode id))
                 (rx/observe-on :async)))))))))

(defn move-shapes-into-frame [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)

            to-move-shapes (->> (cph/get-immediate-children objects)
                                (remove cph/frame-shape?)
                                (d/enumerate)
                                (filterv (comp shapes :id second))
                                (mapv second))

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/change-parent frame-id to-move-shapes 0))]

        (rx/of (dch/commit-changes changes))))))

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

            groups-to-unmask
            (reduce (fn [group-ids id]
                      ;; When the shape to delete is the mask of a masked group,
                      ;; the mask condition must be removed, and it must be
                      ;; converted to a normal group.
                      (let [obj (get objects id)
                            parent (get objects (:parent-id obj))]
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

            empty-parents-xform
            (comp
             (map (fn [id] (get objects id)))
             (map (fn [{:keys [shapes type] :as obj}]
                    (when (and (= :group type)
                               (zero? (count (remove #(contains? ids %) shapes))))
                      obj)))
             (take-while some?)
             (map :id))

            empty-parents
            ;; Any parent whose children are all deleted, must be deleted too.
            (into (d/ordered-set) empty-parents-xform all-parents)

            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-page page)
                        (pcb/with-objects objects)
                        (pcb/remove-objects all-children)
                        (pcb/remove-objects ids)
                        (pcb/remove-objects empty-parents)
                        (pcb/resize-parents all-parents)
                        (pcb/update-shapes groups-to-unmask
                                           (fn [shape]
                                             (assoc shape :masked-group? false)))
                        (pcb/update-shapes (map :id interacting-shapes)
                                           (fn [shape]
                                             (update shape :interactions
                                               (fn [interactions]
                                                 (when interactions
                                                   (d/removev #(and (csi/has-destination %)
                                                                    (contains? ids (:destination %)))
                                                              interactions))))))
                        (cond->
                          (seq starting-flows)
                          (pcb/update-page-option :flows (fn [flows]
                                                           (reduce #(csp/remove-flow %1 (:id %2))
                                                                   flows
                                                                   starting-flows)))))]

        (rx/of (dch/commit-changes changes))))))

;; --- Add shape to Workspace

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
                         (cph/frame-id-by-position {:x frame-x :y frame-y}))
            shape (-> (cp/make-minimal-shape type)
                      (merge data)
                      (merge {:x x :y y})
                      (assoc :frame-id frame-id)
                      (cp/setup-rect-selrect))]
        (rx/of (add-shape shape))))))

(defn image-uploaded
  [image {:keys [x y]}]
  (ptk/reify ::image-uploaded
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [name width height id mtype]} image
            shape {:name name
                   :width width
                   :height height
                   :x (- x (/ width 2))
                   :y (- y (/ height 2))
                   :metadata {:width width
                              :height height
                              :mtype mtype
                              :id id}}]
        (rx/of (create-and-add-shape :image x y shape))))))


