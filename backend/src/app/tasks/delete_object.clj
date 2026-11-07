;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.delete-object
  "A generic task for object deletion cascade handling"
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def ^:dynamic *team-deletion* false)

(def ^:dynamic *cascade*
  "Accumulates the ids marked deleted by the current cascade execution,
  grouped by entity. Bound by the task entry point; nil on direct calls
  (e.g. objects-gc, whose purges are announced by their own procs)."
  nil)

(defn- record
  [kind id]
  (when (some? *cascade*)
    (swap! *cascade* update kind conj id)))

(defn- top-exists?
  "The cascade marks blindly, so an unknown top id would report phantom
  rows. Every queue submission and SREPL call names a real row; only
  check, never change behavior: a missing row just skips the run."
  [{:keys [::db/conn]} {:keys [object id]}]
  (let [object (when (some? object) (keyword (name object)))]
    (if (contains? #{:team :project :file :profile} object)
      (some? (db/get* conn object {:id id}
                      {::db/remove-deleted false
                       ::sql/columns [:id]}))
      true)))

(defmulti delete-object
  (fn [_ props] (:object props)))

(defmethod delete-object :snapshot
  [{:keys [::db/conn] :as cfg} {:keys [id file-id deleted-at]}]
  (l/trc :obj "snapshot" :id (str id) :file-id (str file-id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :file-change
              {:deleted-at deleted-at}
              {:id id :file-id file-id}
              {::db/return-keys false})

  (db/update! conn :file-data
              {:deleted-at deleted-at}
              {:id id :file-id file-id :type "snapshot"}
              {::db/return-keys false}))

(defmethod delete-object :file
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at file-ids]}]
  (when-let [file (db/get* conn :file {:id id}
                           {::db/remove-deleted false
                            ::sql/columns [:id :is-shared]})]

    (l/trc :obj "file" :id (str id)
           :deleted-at (ct/format-inst deleted-at))

    (db/update! conn :file
                {:deleted-at deleted-at
                 :is-shared false}
                {:id id}
                {::db/return-keys false})

    (when (and (:is-shared file)
               (not *team-deletion*))
      ;; NOTE: we don't prevent file deletion on absorb operation failure
      (try
        (db/tx-run! cfg files/absorb-library id :file-ids file-ids)
        (catch Throwable cause
          (l/warn :hint "error on absorbing library"
                  :file-id id
                  :cause cause))))

    ;; Mark file change to be deleted, but only the rows that do not carry a
    ;; date yet. A revision is born already scheduled for deletion one hour
    ;; later, and rewriting its date here would push it out to the trash
    ;; lifetime; the collector would still take it, just much later than it
    ;; should.
    (db/update! conn :file-change
                {:deleted-at deleted-at}
                {:file-id id :deleted-at nil}
                {::db/return-keys false})

    ;; Mark file data fragment to be deleted
    (db/update! conn :file-data
                {:deleted-at deleted-at}
                {:file-id id}
                {::db/return-keys false})

    ;; Mark file media objects to be deleted
    (db/update! conn :file-media-object
                {:deleted-at deleted-at}
                {:file-id id}
                {::db/return-keys false})

    ;; Mark thumbnails to be deleted
    (db/update! conn :file-thumbnail
                {:deleted-at deleted-at}
                {:file-id id}
                {::db/return-keys false})

    (db/update! conn :file-tagged-object-thumbnail
                {:deleted-at deleted-at}
                {:file-id id}
                {::db/return-keys false})

    (record :files id)))

(defmethod delete-object :project
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :obj "project" :id (str id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :project
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (doseq [file (db/query conn :file
                         {:project-id id}
                         {::db/columns [:id :deleted-at]})]
    (delete-object cfg (assoc file
                              :object :file
                              :deleted-at deleted-at)))

  (record :projects id))

(defmethod delete-object :team
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :obj "team" :id (str id)
         :deleted-at (ct/format-inst deleted-at))
  (db/update! conn :team
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (db/update! conn :team-font-variant
              {:deleted-at deleted-at}
              {:team-id id}
              {::db/return-keys false})

  (binding [*team-deletion* true]
    (doseq [project (db/query conn :project
                              {:team-id id}
                              {::db/columns [:id :deleted-at]})]
      (delete-object cfg (assoc project
                                :object :project
                                :deleted-at deleted-at))))

  (record :teams id))

(defmethod delete-object :profile
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :obj "profile" :id (str id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :profile
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (doseq [team (profile/get-owned-teams conn id)]
    (jobs/heartbeat cfg)
    (delete-object cfg (assoc team
                              :object :team
                              :deleted-at deleted-at))))

(defmethod delete-object :default
  [_cfg props]
  (l/wrn :obj (:object props) :hint "not implementation found"))

(def schema:delete-object-params
  [:map
   [:object [:enum :snapshot :team :project :profile :file]]
   [:deleted-at ::ct/inst]
   [:id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]
   [:file-ids {:optional true} [:vector ::sm/uuid]]])

(defn execute-delete-object
  "Plain job handler: run the delete-object multimethod on the provided
  params inside a single transaction, tracking the cascade and reporting
  it as an audit event when anything was marked."
  [cfg params]
  (db/tx-run! cfg
              (fn [cfg]
                (when (top-exists? cfg params)
                  (binding [*cascade* (atom {:teams [] :projects [] :files []})]
                    (delete-object cfg params)
                    (let [{:keys [teams projects files]} @*cascade*]
                      (when (or (seq teams) (seq projects) (seq files))
                        (audit/submit cfg {:name "delete-object-with-cascade"
                                           :type "action"
                                           :props {:object (:object params)
                                                   :id (:id params)
                                                   :deleted-at (:deleted-at params)
                                                   :cascade {:team-ids teams
                                                             :project-ids projects
                                                             :file-ids files}}
                                           :context {:triggered-by "delete-object"}}))))))))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :delete-object
   ::jobs/schema    schema:delete-object-params
   ::jobs/handler
   (fn [_context params]
     (execute-delete-object cfg params))
   ::jobs/decoder   (sm/decoder schema:delete-object-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:delete-object-params)})
