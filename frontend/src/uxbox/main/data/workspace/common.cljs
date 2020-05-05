;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.common
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.main.worker :as uw]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.geom.snap :as snap]))

;; --- Protocols

(defprotocol IBatchedChange)
(defprotocol IUpdateGroup
  (get-ids [this]))

(declare setup-selection-index)
(declare update-selection-index)
(declare update-snap-data)
(declare reset-undo)
(declare append-undo)

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
       (let [page-id (:current-page-id state)
             state (update-in state [:workspace-pages page-id :data] cp/process-changes changes)]
         (cond-> state
           commit-local? (update-in [:workspace-data page-id] cp/process-changes changes))))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page (:workspace-page state)
             uidx (get-in state [:workspace-local :undo-index] ::not-found)]
         (rx/concat
          (rx/of (update-selection-index (:id page))
                 (update-snap-data (:id page)))

          (when (and save-undo? (not= uidx ::not-found))
            (rx/of (reset-undo uidx)))

          (when save-undo?
            (let [entry {:undo-changes undo-changes
                         :redo-changes changes}]
              (rx/of (append-undo entry))))))))))

(defn- generate-operations
  [ma mb]
  (let [ma-keys (set (keys ma))
        mb-keys (set (keys mb))
        added (set/difference mb-keys ma-keys)
        removed (set/difference ma-keys mb-keys)
        both (set/intersection ma-keys mb-keys)]
    (d/concat
     (mapv #(array-map :type :set :attr % :val (get mb %)) added)
     (mapv #(array-map :type :set :attr % :val nil) removed)
     (loop [k (first both)
            r (rest both)
            rs []]
       (if k
         (let [vma (get ma k)
               vmb (get mb k)]
           (if (= vma vmb)
             (recur (first r) (rest r) rs)
             (recur (first r) (rest r) (conj rs {:type :set
                                                 :attr k
                                                 :val vmb}))))
         rs)))))

(defn- generate-changes
  [prev curr]
  (letfn [(impl-diff [res id]
            (let [prev-obj (get-in prev [:objects id])
                  curr-obj (get-in curr [:objects id])
                  ops (generate-operations (dissoc prev-obj :shapes :frame-id)
                                           (dissoc curr-obj :shapes :frame-id))]
              (if (empty? ops)
                res
                (conj res {:type :mod-obj
                           :operations ops
                           :id id}))))]
    (reduce impl-diff [] (set/union (set (keys (:objects prev)))
                                    (set (keys (:objects curr)))))))

(defn diff-and-commit-changes
  [page-id]
  (ptk/reify ::diff-and-commit-changes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            curr (get-in state [:workspace-data page-id])
            prev (get-in state [:workspace-pages page-id :data])

            changes (generate-changes prev curr)
            undo-changes (generate-changes curr prev)]
        (when-not (empty? changes)
          (rx/of (commit-changes changes undo-changes)))))))

;; --- Selection Index Handling

(defn- setup-selection-index
  [{:keys [file pages] :as bundle}]
  (ptk/reify ::setup-selection-index
    ptk/WatchEvent
    (watch [_ state stream]
      (let [msg {:cmd :selection/create-index
                 :file-id (:id file)
                 :pages pages}]
        (->> (uw/ask! msg)
             (rx/map (constantly ::index-initialized)))))))


(defn update-selection-index
  [page-id]
  (ptk/reify ::update-selection-index
    ptk/EffectEvent
    (effect [_ state stream]
      (let [objects (get-in state [:workspace-pages page-id :data :objects])
            lookup  #(get objects %)]
        (uw/ask! {:cmd :selection/update-index
                  :page-id page-id
                  :objects objects})))))

(defn update-snap-data
  [page-id]
  (ptk/reify ::update-snap-data
    ptk/UpdateEvent
    (update [_ state]
      (let [page  (get-in state [:workspace-pages page-id])
            objects (get-in page [:data :objects])]
        (-> state
            (assoc :workspace-snap-data (snap/initialize-snap-data objects)))))))

;; --- Common Helpers & Events

;; TODO: move
(defn retrieve-toplevel-shapes
  [objects]
  (let [lookup #(get objects %)
        root   (lookup uuid/zero)
        childs (:shapes root)]
    (loop [id  (first childs)
           ids (rest childs)
           res []]
      (if (nil? id)
        res
        (let [obj (lookup id)
              typ (:type obj)]
          (recur (first ids)
                 (rest ids)
                 (if (= :frame typ)
                   (into res (:shapes obj))
                   (conj res id))))))))

(defn- calculate-frame-overlap
  [objects shape]
  (let [rshp (geom/shape->rect-shape shape)

        xfmt (comp
              (filter #(= :frame (:type %)))
              (filter #(not= (:id shape) (:id %)))
              (filter #(not= uuid/zero (:id %)))
              (filter #(geom/overlaps? % rshp)))

        frame (->> (vals objects)
                   (sequence xfmt)
                   (first))]

    (or (:id frame) uuid/zero)))

(defn- calculate-shape-to-frame-relationship-changes
  [objects ids]
  (loop [id  (first ids)
         ids (rest ids)
         rch []
         uch []]
    (if (nil? id)
      [rch uch]
      (let [obj (get objects id)
            fid (calculate-frame-overlap objects obj)]
        (if (not= fid (:frame-id obj))
          (recur (first ids)
                 (rest ids)
                 (conj rch {:type :mov-objects
                            :parent-id fid
                            :shapes [id]})
                 (conj uch {:type :mov-objects
                            :parent-id (:frame-id obj)
                            :shapes [id]}))
          (recur (first ids)
                 (rest ids)
                 rch
                 uch))))))

(defn rehash-shape-frame-relationship
  [ids]
  (ptk/reify ::rehash-shape-frame-relationship
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (get-in state [:workspace-data page-id :objects])
            ids     (retrieve-toplevel-shapes objects)
            [rch uch] (calculate-shape-to-frame-relationship-changes objects ids)]
        (when-not (empty? rch)
          (rx/of (commit-changes rch uch {:commit-local? true})))))))

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
      (let [page-id (:current-page-id state)]
        (-> state
            (update-in [:workspace-data page-id] cp/process-changes changes)
            (assoc-in [:workspace-local :undo-index] index))))))

(defn- reset-undo
  [index]
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-local dissoc :undo-index)
          (update-in [:workspace-local :undo]
                     (fn [queue]
                       (into [] (take (inc index) queue))))))))

(defn- append-undo
  [entry]
  (us/verify ::undo-entry entry)
  (ptk/reify ::append-undo
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :undo] (fnil conj-undo-entry []) entry))))

(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:workspace-local state)
            undo (:undo local [])
            index (or (:undo-index local)
                      (dec (count undo)))]
        (when-not (or (empty? undo) (= index -1))
          (let [changes (get-in undo [index :undo-changes])]
            (rx/of (materialize-undo changes (dec index))
                   (commit-changes changes [] {:save-undo? false}))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (:workspace-local state)
            undo (:undo local [])
            index (or (:undo-index local)
                      (dec (count undo)))]
        (when-not (or (empty? undo) (= index (dec (count undo))))
          (let [changes (get-in undo [(inc index) :redo-changes])]
            (rx/of (materialize-undo changes (inc index))
                   (commit-changes changes [] {:save-undo? false}))))))))

(def reinitialize-undo
  (ptk/reify ::reset-undo
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :undo-index :undo))))


