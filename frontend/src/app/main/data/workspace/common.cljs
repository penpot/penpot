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
   [app.common.spec :as us]
   [app.common.types.interactions :as cti]
   [app.common.types.page-options :as cto]
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
    (watch [_ _ _]
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
    (watch [it state _]
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
         (rx/of (dch/commit-changes {:redo-changes rchanges
                                     :undo-changes uchanges
                                     :origin it})
                (select-shapes (d/ordered-set id)))
         (when (= :text (:type attrs))
           (->> (rx/of (start-edition-mode id))
                (rx/observe-on :async))))))))

(defn move-shapes-into-frame [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [it state _]
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
        (rx/of (dch/commit-changes {:redo-changes rchanges
                                    :undo-changes uchanges
                                    :origin it}))))))

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
            options (wsh/lookup-page-options state page-id)

            ids     (cp/clean-loops objects ids)
            flows   (:flows options)

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
                        (some #(and (cti/has-destination %)
                                    (contains? ids (:destination %)))
                              interactions)))
                    (vals objects))

            starting-flows
            (filter #(contains? ids (:starting-frame %)) flows)

            empty-parents-xform
            (comp
             (map (fn [id] (get objects id)))
             (map (fn [{:keys [shapes type] :as obj}]
                    (when (and (= :group type)
                               (zero? (count (remove #(contains? ids %) shapes))))
                      obj)))
             (take-while some?)
             (map :id))

            all-parents
            (reduce (fn [res id]
                      (into res (cp/get-parents id objects)))
                    (d/ordered-set)
                    ids)

            all-children
            (->> ids
                 (reduce (fn [res id]
                           (into res (cp/get-children id objects)))
                         [])
                 (reverse)
                 (into (d/ordered-set)))

            empty-parents
            (into (d/ordered-set) empty-parents-xform all-parents)

            mk-del-obj-xf
            (comp (filter (partial contains? objects))
                  (map (fn [id]
                         {:type :del-obj
                          :page-id page-id
                          :id id})))

            mk-add-obj-xf
            (comp (filter (partial contains? objects))
                  (map (fn [id]
                         (let [item (get objects id)]
                           {:type :add-obj
                            :id (:id item)
                            :page-id page-id
                            :index (cp/position-on-parent id objects)
                            :frame-id (:frame-id item)
                            :parent-id (:parent-id item)
                            :obj item}))))

            mk-mod-touched-xf
            (comp (filter (partial contains? objects))
                  (map (fn [id]
                         (let [parent (get objects id)]
                           {:type :mod-obj
                            :page-id page-id
                            :id (:id parent)
                            :operations [{:type :set-touched
                                          :touched (:touched parent)}]}))))

            mk-mod-int-del-xf
            (comp (filter some?)
                  (map (fn [obj]
                         {:type :mod-obj
                          :page-id page-id
                          :id (:id obj)
                          :operations [{:type :set
                                        :attr :interactions
                                        :val (vec (remove (fn [interaction]
                                                            (and (cti/has-destination interaction)
                                                                 (contains? ids (:destination interaction))))
                                                          (:interactions obj)))}]})))
            mk-mod-int-add-xf
            (comp (filter some?)
                  (map (fn [obj]
                         {:type :mod-obj
                          :page-id page-id
                          :id (:id obj)
                          :operations [{:type :set
                                        :attr :interactions
                                        :val (:interactions obj)}]})))

            mk-mod-del-flow-xf
            (comp (filter some?)
                  (map (fn [flow]
                         {:type :set-option
                          :page-id page-id
                          :option :flows
                          :value (cto/remove-flow flows (:id flow))})))

            mk-mod-add-flow-xf
            (comp (filter some?)
                  (map (fn [_]
                         {:type :set-option
                          :page-id page-id
                          :option :flows
                          :value flows})))

            mk-mod-unmask-xf
            (comp (filter (partial contains? objects))
                  (map (fn [id]
                         {:type :mod-obj
                          :page-id page-id
                          :id id
                          :operations [{:type :set
                                        :attr :masked-group?
                                        :val false}]})))

            mk-mod-mask-xf
            (comp (filter (partial contains? objects))
                  (map (fn [id]
                         {:type :mod-obj
                          :page-id page-id
                          :id id
                          :operations [{:type :set
                                        :attr :masked-group?
                                        :val true}]})))

            rchanges
            (-> []
                (into mk-del-obj-xf all-children)
                (into mk-del-obj-xf ids)
                (into mk-del-obj-xf empty-parents)
                (conj {:type :reg-objects
                       :page-id page-id
                       :shapes (vec all-parents)})
                (into mk-mod-unmask-xf groups-to-unmask)
                (into mk-mod-int-del-xf interacting-shapes)
                (into mk-mod-del-flow-xf starting-flows))

            uchanges
            (-> []
                (into mk-add-obj-xf (reverse empty-parents))
                (into mk-add-obj-xf (reverse ids))
                (into mk-add-obj-xf (reverse all-children))
                (conj {:type :reg-objects
                       :page-id page-id
                       :shapes (vec all-parents)})
                (into mk-mod-touched-xf (reverse all-parents))
                (into mk-mod-mask-xf groups-to-unmask)
                (into mk-mod-int-add-xf interacting-shapes)
                (into mk-mod-add-flow-xf starting-flows))]

        ;; (println "================ rchanges")
        ;; (cljs.pprint/pprint rchanges)
        ;; (println "================ uchanges")
        ;; (cljs.pprint/pprint uchanges)
        (rx/of (dch/commit-changes {:redo-changes rchanges
                                    :undo-changes uchanges
                                    :origin it}))))))

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


