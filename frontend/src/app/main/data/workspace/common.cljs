;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.common
  (:require
   [app.common.data :as d]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.worker :as uw]
   [app.main.streams :as ms]
   [app.util.logging :as log]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(s/def ::shape-attrs ::cp/shape-attrs)
(s/def ::set-of-string (s/every string? :kind set?))
(s/def ::ordered-set-of-uuid (s/every uuid? :kind d/ordered-set?))
;; --- Protocols

(declare setup-selection-index)
(declare update-indices)
(declare reset-undo)
(declare append-undo)


;; --- Helpers

(defn lookup-page-objects
  ([state]
   (lookup-page-objects state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id :objects])))

(defn lookup-page-options
  ([state]
   (lookup-page-options state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id :options])))

(defn interrupt? [e] (= e :interrupt))

(defn lookup-component-objects
  ([state component-id]
   (get-in state [:workspace-data :components component-id :objects])))


;; --- Changes Handling

(defonce page-change? #{:add-page :mod-page :del-page :mov-page})

(defn commit-changes
  ([changes undo-changes]
   (commit-changes changes undo-changes {}))
  ([changes undo-changes {:keys [save-undo?
                                 commit-local?
                                 file-id]
                          :or {save-undo? true
                               commit-local? false}
                          :as opts}]
   (us/verify ::cp/changes changes)
   ;; (us/verify ::cp/changes undo-changes)
   (log/debug :msg "commit-changes"
              :js/changes changes
              :js/undo-changes undo-changes)

   (let [error (volatile! nil)]
     (ptk/reify ::commit-changes
       cljs.core/IDeref
       (-deref [_] {:file-id file-id :changes changes})

       ptk/UpdateEvent
       (update [_ state]
         (let [current-file-id (get state :current-file-id)
               file-id (or file-id current-file-id)
               path1   (if (= file-id current-file-id)
                         [:workspace-file :data]
                         [:workspace-libraries file-id :data])
               path2   (if (= file-id current-file-id)
                         [:workspace-data]
                         [:workspace-libraries file-id :data])]
           (try
             (us/verify ::spec/changes changes)
             (let [state (update-in state path1 cp/process-changes changes false)]
               (cond-> state
                 commit-local? (update-in path2 cp/process-changes changes false)))
             (catch :default e
               (vreset! error e)
               state))))

       ptk/WatchEvent
       (watch [_ state stream]
         (when-not @error
           (let [;; adds page-id to page changes (that have the `id` field instead)
                 add-page-id
                 (fn [{:keys [id type page] :as change}]
                   (cond-> change
                     (page-change? type)
                     (assoc :page-id (or id (:id page)))))

                 changes-by-pages
                 (->> changes
                      (map add-page-id)
                      (remove #(nil? (:page-id %)))
                      (group-by :page-id))

                 process-page-changes
                 (fn [[page-id changes]]
                   (update-indices page-id changes))]
             (rx/concat
              (rx/from (map process-page-changes changes-by-pages))

              (when (and save-undo? (seq undo-changes))
                (let [entry {:undo-changes undo-changes
                             :redo-changes changes}]
                  (rx/of (append-undo entry))))))))))))

(defn generate-operations
  ([ma mb] (generate-operations ma mb false))
  ([ma mb undo?]
   (let [ops (let [ma-keys (set (keys ma))
                   mb-keys (set (keys mb))
                   added   (set/difference mb-keys ma-keys)
                   removed (set/difference ma-keys mb-keys)
                   both    (set/intersection ma-keys mb-keys)]
               (d/concat
                 (mapv #(array-map :type :set :attr % :val (get mb %)) added)
                 (mapv #(array-map :type :set :attr % :val nil) removed)
                 (loop [items  (seq both)
                        result []]
                   (if items
                     (let [k   (first items)
                           vma (get ma k)
                           vmb (get mb k)]
                       (if (= vma vmb)
                         (recur (next items) result)
                         (recur (next items)
                                (conj result {:type :set
                                              :attr k
                                              :val vmb
                                              :ignore-touched undo?}))))
                     result))))]
     (if undo?
       (conj ops {:type :set-touched :touched (:touched mb)})
       ops))))

(defn generate-changes
  [page-id objects1 objects2]
  (letfn [(impl-diff [res id]
            (let [obj1 (get objects1 id)
                  obj2 (get objects2 id)
                  ops  (generate-operations (dissoc obj1 :shapes :frame-id)
                                            (dissoc obj2 :shapes :frame-id))]
              (if (empty? ops)
                res
                (conj res {:type :mod-obj
                           :page-id page-id
                           :operations ops
                           :id id}))))]
    (reduce impl-diff [] (set/union (set (keys objects1))
                                    (set (keys objects2))))))

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

(defn update-indices
  [page-id changes]
  (ptk/reify ::update-indices
    ptk/EffectEvent
    (effect [_ state stream]
      (uw/ask! {:cmd :update-page-indices
                :page-id page-id
                :changes changes}))))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Undo / Redo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::undo-changes ::cp/changes)
(s/def ::redo-changes ::cp/changes)
(s/def ::undo-entry
  (s/keys :req-un [::undo-changes ::redo-changes]))

(def MAX-UNDO-SIZE 50)

(defn- conj-undo-entry
  [undo data]
  (let [undo (conj undo data)
        cnt  (count undo)]
    (if (> cnt MAX-UNDO-SIZE)
      (subvec undo (- cnt MAX-UNDO-SIZE))
      undo)))

(defn- materialize-undo
  [changes index]
  (ptk/reify ::materialize-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-data cp/process-changes changes)
          (assoc-in [:workspace-undo :index] index)))))

(defn- reset-undo
  [index]
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-undo dissoc :undo-index)
          (update-in [:workspace-undo :items] (fn [queue] (into [] (take (inc index) queue))))))))

(defn- add-undo-entry
  [state entry]
  (if (and entry
           (not-empty (:undo-changes entry))
           (not-empty (:redo-changes entry)))
    (let [index (get-in state [:workspace-undo :index] -1)
          items (get-in state [:workspace-undo :items] [])
          items (->> items (take (inc index)) (into []))
          items (conj-undo-entry items entry)]
      (-> state
          (update :workspace-undo assoc :items items
                                        :index (min (inc index)
                                                    (dec MAX-UNDO-SIZE)))))
    state))

(defn- accumulate-undo-entry
  [state {:keys [undo-changes redo-changes]}]
  (-> state
      (update-in [:workspace-undo :transaction :undo-changes] #(into undo-changes %))
      (update-in [:workspace-undo :transaction :redo-changes] #(into % redo-changes))))

(defn- append-undo
  [entry]
  (us/verify ::undo-entry entry)
  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
      (if (get-in state [:workspace-undo :transaction])
        (accumulate-undo-entry state entry)
        (add-undo-entry state entry)))))

(defonce empty-tx {:undo-changes [] :redo-changes []})

(defn start-undo-transaction []
  (ptk/reify ::start-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      ;; We commit the old transaction before starting the new one
      (let [current-tx (get-in state [:workspace-undo :transaction])]
        (cond-> state
          (nil? current-tx) (assoc-in [:workspace-undo :transaction] empty-tx))))))

(defn discard-undo-transaction []
  (ptk/reify ::discard-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-undo dissoc :transaction))))

(defn commit-undo-transaction []
  (ptk/reify ::commit-undo-transaction
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (add-undo-entry (get-in state [:workspace-undo :transaction]))
          (update :workspace-undo dissoc :transaction)))))

(def pop-undo-into-transaction
  (ptk/reify ::last-undo-into-transaction
    ptk/UpdateEvent
    (update [_ state]
      (let [index (get-in state [:workspace-undo :index] -1)]

        (cond-> state
          (>= index 0) (accumulate-undo-entry (get-in state [:workspace-undo :items index]))
          (>= index 0) (update-in [:workspace-undo :index] dec))))))

(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [undo  (:workspace-undo state)
            items (:items undo)
            index (or (:index undo) (dec (count items)))]
        (when-not (or (empty? items) (= index -1))
          (let [changes (get-in items [index :undo-changes])]
            (rx/of (materialize-undo changes (dec index))
                   (commit-changes changes [] {:save-undo? false}))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [undo  (:workspace-undo state)
            items (:items undo)
            index (or (:index undo) (dec (count items)))]
        (when-not (or (empty? items) (= index (dec (count items))))
          (let [changes (get-in items [(inc index) :redo-changes])]
            (rx/of (materialize-undo changes (inc index))
                   (commit-changes changes [] {:save-undo? false}))))))))

(def reinitialize-undo
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-undo {}))))



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

(s/def ::coll-of-uuid
  (s/every ::us/uuid))

(defn update-shapes
  ([ids f] (update-shapes ids f nil))
  ([ids f {:keys [reg-objects?] :or {reg-objects? false}}]
   (us/assert ::coll-of-uuid ids)
   (us/assert fn? f)
   (ptk/reify ::update-shapes
     ptk/WatchEvent
     (watch [_ state stream]
       (let [page-id (:current-page-id state)
             objects (lookup-page-objects state page-id)]
         (loop [ids (seq ids)
                rch []
                uch []]
           (if (nil? ids)
             (rx/of (commit-changes
                     (cond-> rch reg-objects? (conj {:type :reg-objects :page-id page-id :shapes (vec ids)}))
                     (cond-> uch reg-objects? (conj {:type :reg-objects :page-id page-id :shapes (vec ids)}))
                     {:commit-local? true}))

             (let [id   (first ids)
                   obj1 (get objects id)
                   obj2 (f obj1)
                   rch-operations (generate-operations obj1 obj2)
                   uch-operations (generate-operations obj2 obj1 true)
                   rchg {:type :mod-obj
                         :page-id page-id
                         :operations rch-operations
                         :id id}
                   uchg {:type :mod-obj
                         :page-id page-id
                         :operations uch-operations
                         :id id}]
               (recur (next ids)
                      (if (empty? rch-operations) rch (conj rch rchg))
                      (if (empty? uch-operations) uch (conj uch uchg)))))))))))


(defn update-shapes-recursive
  [ids f]
  (us/assert ::coll-of-uuid ids)
  (us/assert fn? f)
  (letfn [(impl-get-children [objects id]
            (cons id (cp/get-children id objects)))

          (impl-gen-changes [objects page-id ids]
            (loop [sids (seq ids)
                   cids (seq (impl-get-children objects (first sids)))
                   rchanges []
                   uchanges []]
              (cond
                (nil? sids)
                [rchanges uchanges]

                (nil? cids)
                (recur (next sids)
                       (seq (impl-get-children objects (first (next sids))))
                       rchanges
                       uchanges)

                :else
                (let [id   (first cids)
                      obj1 (get objects id)
                      obj2 (f obj1)
                      rops (generate-operations obj1 obj2)
                      uops (generate-operations obj2 obj1 true)
                      rchg {:type :mod-obj
                            :page-id page-id
                            :operations rops
                            :id id}
                      uchg {:type :mod-obj
                            :page-id page-id
                            :operations uops
                            :id id}]
                  (recur sids
                         (next cids)
                         (conj rchanges rchg)
                         (conj uchanges uchg))))))]
    (ptk/reify ::update-shapes-recursive
      ptk/WatchEvent
      (watch [_ state stream]
        (let [page-id  (:current-page-id state)
              objects  (lookup-page-objects state page-id)
              [rchanges uchanges] (impl-gen-changes objects page-id (seq ids))]
        (rx/of (commit-changes rchanges uchanges {:commit-local? true})))))))


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
             objects (lookup-page-objects state page-id)]
         (rx/of (expand-all-parents ids objects))))))

;; --- Start shape "edition mode"

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data :pages-index page-id :objects])]
        ;; Can only edit objects that exist
        (if (contains? objects id)
          (-> state
              (assoc-in [:workspace-local :selected] #{id})
              (assoc-in [:workspace-local :edition] id))
          state)))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter interrupt?)
           (rx/take 1)
           (rx/map (constantly clear-edition-mode))))))

(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :hover] disj id)
            (update :workspace-local dissoc :edition))))))

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
  [page-id objects selected attrs]
  (let [id    (:id attrs)
        shape (gpr/setup-proportions attrs)

        default-attrs (if (= :frame (:type shape))
                        cp/default-frame-attrs
                        cp/default-shape-attrs)

        shape    (merge default-attrs shape)

        not-frame? #(not (= :frame (get-in objects [% :type])))
        selected (into #{} (filter not-frame?) selected)

        [frame-id parent-id index] (get-shape-layer-position objects selected attrs)

        redo-changes  [{:type :add-obj
                        :id id
                        :page-id page-id
                        :frame-id frame-id
                        :parent-id parent-id
                        :index index
                        :obj shape}
                       {:type :reg-objects
                        :page-id page-id
                        :shapes [id]}]
        undo-changes  [{:type :del-obj
                        :page-id page-id
                        :id id}]]

    [redo-changes undo-changes]))

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (lookup-page-objects state page-id)

            id (or (:id attrs) (uuid/next))
            name (-> objects
                     (retrieve-used-names)
                     (generate-unique-name (:name attrs)))

            selected (get-in state [:workspace-local :selected])

            [rchanges uchanges] (add-shape-changes
                                 page-id
                                 objects
                                 selected
                                 (-> attrs
                                     (assoc :id id )
                                     (assoc :name name)))]

        (rx/concat
         (rx/of (commit-changes rchanges uchanges {:commit-local? true})
                (select-shapes (d/ordered-set id)))
         (when (= :text (:type attrs))
           (->> (rx/of (start-edition-mode id))
                (rx/observe-on :async))))))))

(defn move-shapes-into-frame [frame-id shapes]
  (ptk/reify ::move-shapes-into-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects (lookup-page-objects state page-id)
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
        (rx/of (commit-changes rchanges uchanges {:commit-local? true}))))))

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
            frame-id (-> (lookup-page-objects state page-id)
                         (cp/frame-id-by-position {:x frame-x :y frame-y}))
            shape (-> (cp/make-minimal-shape type)
                      (merge data)
                      (merge {:x x :y y})
                      (assoc :frame-id frame-id)
                      (gsh/setup-selrect))]
        (rx/of (add-shape shape))))))

(defn image-uploaded [image x y]
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


