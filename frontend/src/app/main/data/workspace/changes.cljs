;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.changes
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]
   [app.main.data.workspace.undo :as dwu]
   [app.main.worker :as uw]
   [app.util.logging :as log]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(s/def ::coll-of-uuid
  (s/every ::us/uuid))

(defonce page-change? #{:add-page :mod-page :del-page :mov-page})

(declare commit-changes)

(def commit-changes? (ptk/type? ::commit-changes))

(defn- generate-operations
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

(defn update-shapes
  ([ids f] (update-shapes ids f nil))
  ([ids f {:keys [reg-objects?] :or {reg-objects? false}}]
   (us/assert ::coll-of-uuid ids)
   (us/assert fn? f)
   (ptk/reify ::update-shapes
     ptk/WatchEvent
     (watch [_ state stream]
       (let [page-id (:current-page-id state)
             objects (get-in state [:workspace-data :pages-index page-id :objects])
             reg-objects {:type :reg-objects :page-id page-id :shapes (vec ids)}]
         (loop [ids (seq ids)
                rch []
                uch []]
           (if (nil? ids)
             (rx/of (let [has-rch? (not (empty? rch))
                          has-uch? (not (empty? uch))
                          rch (cond-> rch (and has-rch? reg-objects?) (conj reg-objects))
                          uch (cond-> uch (and has-rch? reg-objects?) (conj reg-objects))]
                      (when (and has-rch? has-uch?)
                        (commit-changes rch uch {:commit-local? true}))))

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
              objects  (get-in state [:workspace-data :pages-index page-id :objects])
              [rchanges uchanges] (impl-gen-changes objects page-id (seq ids))]
          (rx/of (commit-changes rchanges uchanges {:commit-local? true})))))))

(defn update-indices
  [page-id changes]
  (ptk/reify ::update-indices
    ptk/EffectEvent
    (effect [_ state stream]
      (uw/ask! {:cmd :update-page-indices
                :page-id page-id
                :changes changes}))))

(defn commit-changes
  ([changes undo-changes]
   (commit-changes changes undo-changes {}))
  ([changes undo-changes {:keys [save-undo?
                                 commit-local?
                                 file-id]
                          :or {save-undo? true
                               commit-local? false}
                          :as opts}]
   (us/assert ::cp/changes changes)
   (us/assert ::cp/changes undo-changes)
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
             (us/assert ::spec/changes changes)
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
                  (rx/of (dwu/append-undo entry))))))))))))
