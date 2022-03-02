;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.changes
  (:require
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.spec :as us]
   [app.common.spec.change :as spec.change]
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

(defn update-shapes
  ([ids update-fn] (update-shapes ids update-fn nil nil))
  ([ids update-fn keys] (update-shapes ids update-fn nil keys))
  ([ids update-fn page-id {:keys [reg-objects? save-undo? attrs ignore-tree]
                           :or {reg-objects? false save-undo? true attrs nil}}]

   (us/assert ::coll-of-uuid ids)
   (us/assert fn? update-fn)

   (ptk/reify ::update-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id   (or page-id (:current-page-id state))
             objects   (wsh/lookup-page-objects state)
             ids       (into [] (filter some?) ids)

             changes   (reduce
                         (fn [changes id]
                           (pcb/update-shapes changes
                                              [id]
                                              update-fn
                                              {:attrs attrs
                                               :ignore-geometry? (get ignore-tree id)}))
                         (-> (pcb/empty-changes it page-id)
                             (pcb/set-save-undo? save-undo?)
                             (pcb/with-objects objects))
                         ids)]

         (when (seq (:redo-changes changes))
           (let [changes  (cond-> changes
                            reg-objects?
                            (pcb/resize-parents ids))]
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
  [{:keys [redo-changes undo-changes
           origin save-undo? file-id]
    :or {save-undo? true}}]
  (log/debug :msg "commit-changes"
             :js/redo-changes redo-changes
             :js/undo-changes undo-changes)
  (let [error  (volatile! nil)]
    (ptk/reify ::commit-changes
      cljs.core/IDeref
      (-deref [_]

        {:file-id file-id
         :hint-events @st/last-events
         :hint-origin (ptk/type origin)
         :changes redo-changes})

      ptk/UpdateEvent
      (update [_ state]
        (let [current-file-id (get state :current-file-id)
              file-id         (or file-id current-file-id)
              path            (if (= file-id current-file-id)
                                [:workspace-data]
                                [:workspace-libraries file-id :data])]
          (try
            (us/assert ::spec.change/changes redo-changes)
            (us/assert ::spec.change/changes undo-changes)

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
