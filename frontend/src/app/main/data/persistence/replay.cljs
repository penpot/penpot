;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence.replay
  (:require
   [app.common.uuid :as uuid]))

(defn- ordinary-leaf?
  [objects id]
  (loop [id id, seen #{}]
    (when-let [shape (get objects id)]
      (and (= id (:id shape))
           (not (contains? seen id))
           (not-any? #(some? (get shape %))
                     [:component-id :component-file :component-root :shape-ref :main-instance])
           (or (= id uuid/zero)
               (let [parent-id (:parent-id shape)]
                 (and (some #{id} (:shapes (get objects parent-id)))
                      (recur parent-id (conj seen id)))))))))

(defn- get-name-set
  [operations]
  (some #(when (= :set (:type %)) %) operations))

(defn get-rename-target
  "A name-only edit on an ordinary leaf, or nil if it has other effects."
  [data {:keys [type page-id id component-id operations]} undo?]
  (let [op      (get-name-set operations)
        objects (get-in data [:pages-index page-id :objects])
        shape   (get objects id)]
    (when (and (= type :mod-obj)
               (uuid? page-id)
               (nil? component-id)
               (uuid? id) (not= uuid/zero id)
               (= :set (:type op)) (= :name (:attr op)) (string? (:val op))
               (or (= 1 (count operations))
                   (and undo? (= 2 (count operations))
                        (some #{{:type :set-touched :touched nil}} operations)
                        (not (contains? shape :touched))))
               (empty? (:shapes shape))
               (ordinary-leaf? objects id))
      [page-id id])))

(defn- get-renames
  [data changes undo?]
  (reduce (fn [renames change]
            (let [target (get-rename-target data change undo?)]
              (if (and target (not (contains? renames target)))
                (assoc renames target (:val (get-name-set (:operations change))))
                (reduced nil))))
          {} changes))

(defn capture
  "Certifies the inverse against the state before applying a local commit."
  [data {:keys [redo-changes undo-changes]}]
  (when-let [redo (not-empty (get-renames data redo-changes false))]
    (let [undo (get-renames data undo-changes true)]
      (when (and (= (set (keys redo)) (set (keys undo)))
                 (every? (fn [[[page-id id] name]]
                           (= name (get-in data [:pages-index page-id :objects id :name])))
                         undo))
        (set (keys redo))))))

(defn safe-pending?
  "Allows only proven inverses with no intervening updates to their targets."
  [data pending changes remote-history base-id]
  (let [targets (into #{} (mapcat :replay-targets) pending)
        independent? (fn [targets changes]
                       (every? (fn [change]
                                 (when-let [target (get-rename-target data change false)]
                                   (not (contains? targets target))))
                               changes))]
    (or (empty? pending)
        (and (every? (fn [{:keys [replay-base replay-targets undo-uncertain compensation]}]
                       (and (= base-id replay-base)
                            (seq replay-targets)
                            (not (or undo-uncertain compensation))))
                     pending)
             (independent? targets changes)
             (every? (fn [{:keys [remote-seq replay-targets]}]
                       (every? (fn [{:keys [seq changes]}]
                                 (or (<= seq remote-seq) (independent? replay-targets changes)))
                               remote-history))
                     pending)))))

(defn separate-pages?
  "Checks for disjoint pages. Callers must also prove ordinary rename replay."
  [pending changes]
  (and (seq pending)
       (seq changes)
       (let [remote-pages (into #{} (map :page-id) changes)]
         (not-any? #(contains? remote-pages (first %)) (mapcat :replay-targets pending)))))
