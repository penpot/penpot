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
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.worker :as uw]
   [app.util.timers :as ts]
   [app.common.geom.shapes :as geom]))

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



;; --- Changes Handling

(defn commit-changes
  ([changes undo-changes]
   (commit-changes changes undo-changes {}))
  ([changes undo-changes {:keys [save-undo?
                                 commit-local?]
                          :or {save-undo? true
                               commit-local? false}
                          :as opts}]
   (us/verify ::cp/changes changes)
   (us/verify ::cp/changes undo-changes)
   (ptk/reify ::commit-changes
     cljs.core/IDeref
     (-deref [_] changes)

     ptk/UpdateEvent
     (update [_ state]
       (let [state (update-in state [:workspace-file :data] cp/process-changes changes)]
         (cond-> state
           commit-local? (update :workspace-data cp/process-changes changes))))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page-id (:current-page-id state)
             uidx    (get-in state [:workspace-undo :index] ::not-found)]
         (rx/concat
          (when (some :page-id changes)
            (rx/of (update-indices page-id)))

          (when (and save-undo? (not= uidx ::not-found))
            (rx/of (reset-undo uidx)))

          (when (and save-undo? (seq undo-changes))
            (let [entry {:undo-changes undo-changes
                         :redo-changes changes}]
              (rx/of (append-undo entry))))))))))

(defn generate-operations
  [ma mb]
  (let [ma-keys (set (keys ma))
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
                                  :val vmb}))))
         result)))))

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
  [page-id]
  (ptk/reify ::update-indices
    ptk/EffectEvent
    (effect [_ state stream]
      (let [objects (lookup-page-objects state page-id)]
        (uw/ask! {:cmd :update-page-indices
                  :page-id page-id
                  :objects objects})))))

;; --- Common Helpers & Events

(defn- calculate-frame-overlap
  [frames shape]
  (let [xf      (comp
                 (filter #(geom/overlaps? % (:selrect shape)))
                 (take 1))
        frame   (first (into [] xf frames))]
    (or (:id frame) uuid/zero)))

(defn- calculate-shape-to-frame-relationship-changes
  [page-id frames shapes]
  (loop [shape  (first shapes)
         shapes (rest shapes)
         rch    []
         uch    []]
    (if (nil? shape)
      [rch uch]
      (let [fid (calculate-frame-overlap frames shape)]
        (if (not= fid (:frame-id shape))
          (recur (first shapes)
                 (rest shapes)
                 (conj rch {:type :mov-objects
                            :page-id page-id
                            :parent-id fid
                            :shapes [(:id shape)]})
                 (conj uch {:type :mov-objects
                            :page-id page-id
                            :parent-id (:frame-id shape)
                            :shapes [(:id shape)]}))
          (recur (first shapes)
                 (rest shapes)
                 rch
                 uch))))))

(defn rehash-shape-frame-relationship
  [ids]
  (ptk/reify ::rehash-shape-frame-relationship
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (get-in state [:workspace-page :id])
            objects (lookup-page-objects state page-id)

            shapes (cph/select-toplevel-shapes objects)
            frames (cph/select-frames objects)

            [rch uch] (calculate-shape-to-frame-relationship-changes page-id frames shapes)]
        (when-not (empty? rch)
          (rx/of (commit-changes rch uch {:commit-local? true})))))))


(defn get-frame-at-point
  [objects point]
  (let [frames (cph/select-frames objects)]
    (d/seek #(geom/has-point? % point) frames)))


(defn- extract-numeric-suffix
  [basename]
  (if-let [[match p1 p2] (re-find #"(.*)-([0-9]+)$" basename)]
    [p1 (+ 1 (d/parse-integer p2))]
    [basename 1]))

(defn retrieve-used-names
  [objects]
  (into #{} (map :name) (vals objects)))

(s/def ::set-of-string
  (s/every string? :kind set?))

(defn generate-unique-name
  "A unique name generator"
  [used basename]
  (s/assert ::set-of-string used)
  (s/assert ::us/string basename)
  (let [[prefix initial] (extract-numeric-suffix basename)]
    (loop [counter initial]
      (let [candidate (str prefix "-" counter)]
        (if (contains? used candidate)
          (recur (inc counter))
          candidate)))))

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
  (let [undo (conj undo data)]
    (if (> (count undo) MAX-UNDO-SIZE)
      (into [] (take MAX-UNDO-SIZE undo))
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

(defn- append-undo
  [entry]
  (us/verify ::undo-entry entry)
  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-undo :items] (fnil conj-undo-entry []) entry))))

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
        (when-not (or (empty? items) (= index (dec items)))
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
                               (map #(cph/get-parents % objects))
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
                  rchg {:type :mod-obj
                        :page-id page-id
                        :operations (generate-operations obj1 obj2)
                        :id id}
                  uchg {:type :mod-obj
                        :page-id page-id
                        :operations (generate-operations obj2 obj1)
                        :id id}]
              (recur (next ids)
                     (conj rch rchg)
                     (conj uch uchg))))))))))


(defn update-shapes-recursive
  [ids f]
  (us/assert ::coll-of-uuid ids)
  (us/assert fn? f)
  (letfn [(impl-get-children [objects id]
            (cons id (cph/get-children id objects)))

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
                      uops (generate-operations obj2 obj1)
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
