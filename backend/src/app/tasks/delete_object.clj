;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.delete-object
  "A generic task for object deletion cascade handling"
  (:require
   [app.common.data :as d]
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

(def ^:dynamic *deleting-file-ids*
  "Ids of the files the running cascade is going to delete. Bound by
  the `:project` method with its own files so `:file` only absorbs the
  dependents that survive: absorbing into files that die in the same
  run is wasted work. Team deletions need no such set: they bind
  `*team-deletion*` instead, which skips absorb entirely."
  nil)

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
               ;; Team deletions skip absorb entirely: every file dies
               ;; in the same cascade, so no survivor can absorb.
               (not *team-deletion*))
      ;; NOTE: we don't prevent file deletion on absorb operation failure.
      ;; Only live dependents outside this cascade matter: if the whole
      ;; closure dies together there is nothing to absorb and the
      ;; relations die with their files (both FKs are ON DELETE CASCADE).
      ;; DEPRECATED `:file-ids`: jobs queued by the previous release
      ;; already deleted the relations in the RPC, so the live lookup
      ;; finds nothing. Trust their snapshot for one release; new jobs
      ;; never carry the key.
      (let [doomed     (or *deleting-file-ids* #{})
            dependents (if (seq file-ids)
                         (into [] (remove doomed) file-ids)
                         (into [] (remove doomed)
                               (files/get-referenced-files conn id)))]
        (when (seq dependents)
          ;; Runs in the caller transaction: a nested transaction here
          ;; would join it anyway, so the call goes directly. The
          ;; savepoint contains an absorb failure: it rolls back the
          ;; absorb and the relations removal and the cascade goes on.
          ;; Nothing retries the absorb itself: the job succeeds, the
          ;; relations stay, and the later hard delete cascades them
          ;; away with their files.
          (let [sp (db/savepoint conn "absorb-library")]
            (try
              ;; The relations must still exist at this point: removing
              ;; them first is what lets a later hard delete proceed.
              (db/delete! conn :file-library-rel {:library-file-id id})
              (files/absorb-library cfg id :file-ids dependents)
              (catch Throwable cause
                (db/rollback! conn sp)
                (l/warn :hint "error on absorbing library"
                        :file-id id
                        :cause cause))
              (finally
                (db/release! conn sp)))))))

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

  (let [files (db/query conn :file
                        {:project-id id}
                        {::db/columns [:id :deleted-at]})]
    ;; Every file below dies in this run: absorbing a library into
    ;; them is wasted work, so the files only absorb survivors.
    (binding [*deleting-file-ids* (into #{} d/xf:map-id files)]
      (doseq [file files]
        (delete-object cfg (assoc file
                                  :object :file
                                  :deleted-at deleted-at)))))

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
   ;; DEPRECATED: only jobs queued before the cascade owned the
   ;; relations carry it (their RPC already deleted them). Remove once
   ;; no such rows can be left.
   [:file-ids {:optional true} [:vector ::sm/uuid]]])

(defn execute-delete-object
  "Plain job handler: run the delete-object multimethod on the provided
  params, tracking the cascade and reporting it as an audit event when
  anything was marked. Runs inside the caller transaction (the job-def
  handler opens it with `tx-run!`)."
  [cfg params]
  (when (top-exists? cfg params)
    (binding [*cascade* (atom {:teams [] :projects [] :files []})]
      (delete-object cfg params)
      (let [{:keys [teams projects files]} @*cascade*]
        (when (or (seq teams) (seq projects) (seq files))
          ;; Flat team ids for consumers that key on them
          ;; (the storage-usage drain), so they never join
          ;; production tables.
          (let [team-ids (audit/get-team-ids cfg
                                             {:team-ids teams
                                              :project-ids projects
                                              :file-ids files})]
            (audit/submit cfg {:name "delete-object-with-cascade"
                               :type "action"
                               :props {:object (:object params)
                                       :id (:id params)
                                       :deleted-at (:deleted-at params)
                                       :team-ids (vec team-ids)
                                       :cascade {:team-ids teams
                                                 :project-ids projects
                                                 :file-ids files}}
                               :context {:triggered-by "delete-object"}})))))))

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
     (db/tx-run! cfg execute-delete-object params))
   ::jobs/decoder   (sm/decoder schema:delete-object-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:delete-object-params)})
