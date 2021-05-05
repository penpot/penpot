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
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [app.util.logging :as log]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(s/def ::shape-attrs ::cp/shape-attrs)
(s/def ::set-of-string (s/every string? :kind set?))
(s/def ::ordered-set-of-uuid (s/every uuid? :kind d/ordered-set?))


;; --- Helpers

(defn interrupt? [e] (= e :interrupt))

;; --- Selection Index Handling

(defn initialize-indices
  [{:keys [file] :as bundle}]
  (ptk/reify ::setup-selection-index
    ptk/WatchEvent
    (watch [_ state stream]
      (let [msg {:cmd :initialize-indices
                 :file-id (:id file)
                 :data (:data file)}]
        (->> (uw/ask! msg)
             (rx/map (constantly ::index-initialized)))))))



;; --- Common Helpers & Events

(defn get-frame-at-point
  [objects point]
  (let [frames (cp/select-frames objects)]
    (d/seek #(gsh/has-point? % point) frames)))


(defn- extract-numeric-suffix
  [basename]
  (if-let [[match p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn retrieve-used-names
  [objects]
  (into #{} (comp (map :name) (remove nil?)) (vals objects)))


(defn generate-unique-name
  "A unique name generator"
  ([used basename]
   (generate-unique-name used basename false))
  ([used basename prefix-first?]
   (s/assert ::set-of-string used)
   (s/assert ::us/string basename)
   (let [[prefix initial] (extract-numeric-suffix basename)]
     (loop [counter initial]
       (let [candidate (if (and (= 1 counter) prefix-first?)
                         (str prefix)
                         (str prefix "-" counter))]
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
    (watch [_ state stream]
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
                       (dch/commit-changes changes [] {:save-undo? false}))))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (or (some? edition) (not-empty drawing))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index (dec (count items))))
              (let [changes (get-in items [(inc index) :redo-changes])]
                (rx/of (dwu/materialize-undo changes (inc index))
                       (dch/commit-changes changes [] {:save-undo? false}))))))))))

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
                               (map #(cp/get-parents % objects))
                               flatten
                               (filter #(not= % uuid/zero))
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
    (watch [_ state stream]
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
    (watch [_ state stream]
      (let [objects (wsh/lookup-page-objects state)]
        (->> stream
             (rx/filter interrupt?)
             (rx/take 1)
             (rx/map (constantly clear-edition-mode)))))))

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
    ;; Frames are alwasy positioned on the root frame
    [uuid/zero uuid/zero nil]

    ;; Calculate the frame over which we're drawing
    (let [position @ms/mouse-position
          frame-id (:frame-id attrs (cp/frame-id-by-position objects position))
          shape (when-not (empty? selected)
                  (cp/get-base-shape objects selected))]

      ;; When no shapes has been selected or we're over a different frame
      ;; we add it as the latest shape of that frame
      (if (or (not shape) (not= (:frame-id shape) frame-id))
        [frame-id frame-id nil]

        ;; Otherwise, we add it to next to the selected shape
        (let [index (cp/position-on-parent (:id shape) objects)
              {:keys [frame-id parent-id]} shape]
          [frame-id parent-id (inc index)])))))

(defn add-shape-changes
  ([page-id objects selected attrs]
   (add-shape-changes page-id objects selected attrs true))
  ([page-id objects selected attrs reg-object?]
   (let [id    (:id attrs)
         shape (gpr/setup-proportions attrs)

         default-attrs (if (= :frame (:type shape))
                         cp/default-frame-attrs
                         cp/default-shape-attrs)

         shape    (merge default-attrs shape)

         not-frame? #(not (= :frame (get-in objects [% :type])))
         selected (into #{} (filter not-frame?) selected)

         [frame-id parent-id index] (get-shape-layer-position objects selected attrs)

         redo-changes  (cond-> [{:type :add-obj
                                 :id id
                                 :page-id page-id
                                 :frame-id frame-id
                                 :parent-id parent-id
                                 :index index
                                 :obj shape}]
                         reg-object?
                         (conj {:type :reg-objects
                                :page-id page-id
                                :shapes [id]}))
         undo-changes  [{:type :del-obj
                         :page-id page-id
                         :id id}]]

     [redo-changes undo-changes])))

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state page-id)

            id (or (:id attrs) (uuid/next))
            name (-> objects
                     (retrieve-used-names)
                     (generate-unique-name (:name attrs)))

            selected (wsh/lookup-selected state)

            [rchanges uchanges] (add-shape-changes
                                 page-id
                                 objects
                                 selected
                                 (-> attrs
                                     (assoc :id id )
                                     (assoc :name name)))]

        (rx/concat
         (rx/of (dch/commit-changes rchanges uchanges {:commit-local? true})
                (select-shapes (d/ordered-set id)))
         (when (= :text (:type attrs))
           (->> (rx/of (start-edition-mode id))
                (rx/observe-on :async))))))))

(defn move-shapes-into-frame [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)
            to-move-shapes (->> (cp/select-toplevel-shapes objects {:include-frames? false})
                                (filterv #(= (:frame-id %) uuid/zero))
                                (mapv :id)
                                (d/enumerate)
                                (filterv (comp shapes second)))

            rchanges [{:type :mov-objects
                       :parent-id frame-id
                       :frame-id frame-id
                       :page-id page-id
                       :index 0
                       :shapes (mapv second to-move-shapes)}]

            uchanges (->> to-move-shapes
                          (mapv (fn [[index shape-id]]
                                  {:type :mov-objects
                                   :parent-id uuid/zero
                                   :frame-id uuid/zero
                                   :page-id page-id
                                   :index index
                                   :shapes [shape-id]})))]
        (rx/of (dch/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn delete-shapes
  [ids]
  (us/assert (s/coll-of ::us/uuid) ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state page-id)

            get-empty-parents
            (fn [parents]
              (->> parents
                   (map (fn [id]
                          (let [obj (get objects id)]
                            (when (and (= :group (:type obj))
                                       (= 1 (count (:shapes obj))))
                              obj))))
                   (take-while (complement nil?))
                   (map :id)))

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
                      (let [interactions (:interactions shape)]
                        (some ids (map :destination interactions))))
                    (vals objects))

            rchanges
            (d/concat
              (reduce (fn [res id]
                        (let [children (cp/get-children id objects)
                              parents  (cp/get-parents id objects)
                              del-change #(array-map
                                            :type :del-obj
                                            :page-id page-id
                                            :id %)]
                              (d/concat res
                                        (map del-change (reverse children))
                                        [(del-change id)]
                                        (map del-change (get-empty-parents parents))
                                        [{:type :reg-objects
                                          :page-id page-id
                                          :shapes (vec parents)}])))
                      []
                      ids)
              (map #(array-map
                      :type :mod-obj
                      :page-id page-id
                      :id %
                      :operations [{:type :set
                                    :attr :masked-group?
                                    :val false}])
                   groups-to-unmask)
              (map #(array-map
                      :type :mod-obj
                      :page-id page-id
                      :id (:id %)
                      :operations [{:type :set
                                    :attr :interactions
                                    :val (vec (remove (fn [interaction]
                                                        (contains? ids (:destination interaction)))
                                                      (:interactions %)))}])
                   interacting-shapes))


            uchanges
            (d/concat
              (reduce (fn [res id]
                        (let [children    (cp/get-children id objects)
                              parents     (cp/get-parents id objects)
                              parent      (get objects (first parents))
                              add-change  (fn [id]
                                            (let [item (get objects id)]
                                              {:type :add-obj
                                               :id (:id item)
                                               :page-id page-id
                                               :index (cp/position-on-parent id objects)
                                               :frame-id (:frame-id item)
                                               :parent-id (:parent-id item)
                                               :obj item}))]
                          (d/concat res
                                    (map add-change (reverse (get-empty-parents parents)))
                                    [(add-change id)]
                                    (map add-change children)
                                    [{:type :reg-objects
                                      :page-id page-id
                                      :shapes (vec parents)}]
                                    (when (some? parent)
                                      [{:type :mod-obj
                                        :page-id page-id
                                        :id (:id parent)
                                        :operations [{:type :set-touched
                                                      :touched (:touched parent)}]}]))))
                      []
                      ids)
              (map #(array-map
                      :type :mod-obj
                      :page-id page-id
                      :id %
                      :operations [{:type :set
                                    :attr :masked-group?
                                    :val true}])
                   groups-to-unmask)
              (map #(array-map
                      :type :mod-obj
                      :page-id page-id
                      :id (:id %)
                      :operations [{:type :set
                                    :attr :interactions
                                    :val (:interactions %)}])
                   interacting-shapes))]

        ;; (println "================ rchanges")
        ;; (cljs.pprint/pprint rchanges)
        ;; (println "================ uchanges")
        ;; (cljs.pprint/pprint uchanges)
        (rx/of (dch/commit-changes rchanges uchanges {:commit-local? true}))))))


;; --- Add shape to Workspace

(defn- viewport-center
  [state]
  (let [{:keys [x y width height]} (get-in state [:workspace-local :vbox])]
    [(+ x (/ width 2)) (+ y (/ height 2))]))

(defn create-and-add-shape
  [type frame-x frame-y data]
  (ptk/reify ::create-and-add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [width height]} data

            [vbc-x vbc-y] (viewport-center state)
            x (:x data (- vbc-x (/ width 2)))
            y (:y data (- vbc-y (/ height 2)))
            page-id (:current-page-id state)
            frame-id (-> (wsh/lookup-page-objects state page-id)
                         (cp/frame-id-by-position {:x frame-x :y frame-y}))
            shape (-> (cp/make-minimal-shape type)
                      (merge data)
                      (merge {:x x :y y})
                      (assoc :frame-id frame-id)
                      (gsh/setup-selrect))]
        (rx/of (add-shape shape))))))

(defn image-uploaded
  [image {:keys [x y]}]
  (ptk/reify ::image-uploaded
    ptk/WatchEvent
    (watch [_ state stream]
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


