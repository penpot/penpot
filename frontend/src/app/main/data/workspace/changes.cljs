;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.changes
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(s/def ::coll-of-uuid
  (s/every ::us/uuid))

(defonce page-change? #{:add-page :mod-page :del-page :mov-page})

(declare commit-changes)

(def commit-changes? (ptk/type? ::commit-changes))

(defn- generate-operation
  "Given an object old and new versions and an attribute will append into changes
  the set and undo operations"
  [changes attr old new ignore-geometry?]
  (let [old-val (get old attr)
        new-val (get new attr)]
    (if (= old-val new-val)
      changes
      (-> changes
          (update :rops conj {:type :set :attr attr :val new-val :ignore-geometry ignore-geometry?})
          (update :uops conj {:type :set :attr attr :val old-val :ignore-touched true})))))

(defn- update-shape-changes
  "Calculate the changes and undos to be done when a function is applied to a
  single object"
  [changes page-id objects update-fn attrs id ignore-geometry?]
  (let [old-obj (get objects id)
        new-obj (update-fn old-obj)

        attrs (or attrs (d/concat #{} (keys old-obj) (keys new-obj)))

        {rops :rops uops :uops}
        (reduce #(generate-operation %1 %2 old-obj new-obj ignore-geometry?)
                {:rops [] :uops []}
                attrs)

        uops (cond-> uops
               (seq uops)
               (conj {:type :set-touched :touched (:touched old-obj)}))

        change {:type :mod-obj :page-id page-id :id id}]

    (cond-> changes
      (seq rops)
      (update :redo-changes conj (assoc change :operations rops))

      (seq uops)
      (update :undo-changes conj (assoc change :operations uops)))))

(defn update-shapes
  ([ids f] (update-shapes ids f nil))
  ([ids f {:keys [reg-objects? save-undo? attrs ignore-tree]
           :or {reg-objects? false save-undo? true attrs nil}}]

   (us/assert ::coll-of-uuid ids)
   (us/assert fn? f)

   (ptk/reify ::update-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id   (:current-page-id state)
             objects   (wsh/lookup-page-objects state)
             changes   {:redo-changes []
                        :undo-changes []
                        :origin it
                        :save-undo? save-undo?}

             ids       (into [] (filter some?) ids)

             changes   (reduce
                         #(update-shape-changes %1 page-id objects f attrs %2 (get ignore-tree %2))
                         changes ids)]

         (when-not (empty? (:redo-changes changes))
           (let [reg-objs {:type :reg-objects
                           :page-id page-id
                           :shapes ids}
                 changes  (cond-> changes
                            reg-objects?
                            (-> (update :redo-changes conj reg-objs)
                                (update :undo-changes conj reg-objs)))]
             (rx/of (commit-changes changes)))))))))

(defn update-indices
  [page-id changes]
  (ptk/reify ::update-indices
    ptk/EffectEvent
    (effect [_ _ _]
      (uw/ask! {:cmd :update-page-indices
                :page-id page-id
                :changes changes}))))

(defn commit-changes
  [{:keys [redo-changes undo-changes origin save-undo? file-id] :or {save-undo? true}}]
  (log/debug :msg "commit-changes"
             :js/redo-changes redo-changes
             :js/undo-changes undo-changes)
  (let [error  (volatile! nil)
        strace (.-stack (ex-info "" {}))]

    (ptk/reify ::commit-changes
      cljs.core/IDeref
      (-deref [_]
        {:file-id file-id
         :hint-events @st/last-events
         :hint-origin (ptk/type origin)
         :hint-strace strace
         :changes redo-changes})

      ptk/UpdateEvent
      (update [_ state]
        (let [current-file-id (get state :current-file-id)
              file-id         (or file-id current-file-id)
              path            (if (= file-id current-file-id)
                                [:workspace-data]
                                [:workspace-libraries file-id :data])]
          (try
            (us/assert ::spec/changes redo-changes)
            (us/assert ::spec/changes undo-changes)

            ;; (prn "====== commit-changes ======" path)
            ;; (cljs.pprint/pprint redo-changes)
            ;; (cljs.pprint/pprint undo-changes)

            (update-in state path cp/process-changes redo-changes false)

            (catch :default e
              (vreset! error e)
              state))))

      ptk/WatchEvent
      (watch [_ _ _]
        (when-not @error
          (let [;; adds page-id to page changes (that have the `id` field instead)
                add-page-id
                (fn [{:keys [id type page] :as change}]
                  (cond-> change
                    (page-change? type)
                    (assoc :page-id (or id (:id page)))))

                changes-by-pages
                (->> redo-changes
                     (map add-page-id)
                     (remove #(nil? (:page-id %)))
                     (group-by :page-id))

                process-page-changes
                (fn [[page-id _changes]]
                  (update-indices page-id redo-changes))]
            (rx/concat
             (rx/from (map process-page-changes changes-by-pages))

             (when (and save-undo? (seq undo-changes))
               (let [entry {:undo-changes undo-changes
                            :redo-changes redo-changes}]
                 (rx/of (dwu/append-undo entry)))))))))))
