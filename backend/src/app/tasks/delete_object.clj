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
  "Skip missing team and profile rows before the cascade reports them.
  File and project handlers check existence when they lock the row."
  [{:keys [::db/conn]} {:keys [object id]}]
  (if (contains? #{:team :profile} object)
    (some? (db/get* conn object {:id id}
                    {::db/remove-deleted false
                     ::sql/columns [:id]}))
    true))

(defn- earliest-deadline
  [deadline inherited]
  (if (and (some? inherited) (ct/is-before? inherited deadline))
    inherited
    deadline))

(def ^:private sql:date-file-changes
  "UPDATE file_change
      SET deleted_at = ?, updated_at = ?
    WHERE file_id = ?
      AND (deleted_at IS NULL
           OR (deleted_at > ?::timestamptz AND (? OR retained)))")

(def ^:private sql:date-file-data
  "UPDATE file_data
      SET deleted_at = ?
    WHERE file_id = ?
      AND (deleted_at IS NULL
           OR (deleted_at > ?::timestamptz
               AND (? OR type IN ('main', 'fragment')
                      OR (type = 'revision' AND metadata -> 'retained' = 'true'::jsonb))))")

(defn- date-file-history
  [conn id deleted-at purge]
  ;; Retained revisions and their payloads follow the file. Ordinary trash
  ;; does not alter existing snapshot or ephemeral deadlines. An explicit
  ;; purge takes all children at once so their foreign keys cannot block it.
  (db/exec-one! conn [sql:date-file-changes deleted-at (ct/now) id deleted-at purge])
  (db/exec-one! conn [sql:date-file-data deleted-at id deleted-at purge]))

(defmulti delete-object
  (fn [_ props] (:object props)))

(defmethod delete-object :snapshot
  [{:keys [::db/conn] :as cfg} {:keys [id file-id deleted-at]}]
  (l/trc :obj "snapshot" :id (str id) :file-id (str file-id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :file-change
              {:deleted-at deleted-at
               :updated-at (ct/now)}
              {:id id :file-id file-id}
              {::db/return-keys false})

  (db/update! conn :file-data
              {:deleted-at deleted-at}
              {:id id :file-id file-id :type "snapshot"}
              {::db/return-keys false}))

(defn- delete-file
  [{:keys [::db/conn] :as cfg} file deleted-at {:keys [file-ids purge]}]
  (let [{:keys [id is-shared]} file
        deleted-at (earliest-deadline deleted-at (:deleted-at file))]

    (l/trc :obj "file" :id (str id)
           :deleted-at (ct/format-inst deleted-at))

    (db/update! conn :file
                {:deleted-at deleted-at
                 :is-shared false}
                {:id id}
                {::db/return-keys false})

    (when (and is-shared
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
          ;; The relations are dropped unconditionally, before the absorb
          ;; runs: they are dead weight once the library is being
          ;; deleted, regardless of whether the absorb succeeds. A
          ;; failure here fails the job instead of being swallowed,
          ;; like every other write in the cascade.
          (db/delete! conn :file-library-rel {:library-file-id id})
          ;; Runs in the caller transaction: a nested transaction here
          ;; would join it anyway, so the call goes directly. The
          ;; savepoint contains an absorb failure: it rolls back the
          ;; whole absorb and the cascade goes on.
          ;; Nothing retries the absorb itself: the job succeeds and the
          ;; dependents keep the data they had before the absorb ran.
          (let [sp (db/savepoint conn "absorb-library")]
            (try
              (files/absorb-library cfg id :file-ids dependents)
              (catch Throwable cause
                (db/rollback! conn sp)
                (l/warn :hint "error on absorbing library"
                        :file-id id
                        :cause cause))
              (finally
                (db/release! conn sp)))))))

    (date-file-history conn id deleted-at (true? purge))

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

(def ^:private sql:lock-file-for-deletion
  ;; Older purge jobs carry the original request time. Compare it at
  ;; PostgreSQL's timestamp precision, not the application's precision.
  "SELECT f.id, f.deleted_at, f.is_shared, p.deleted_at AS project_deleted_at,
          f.deleted_at = ?::timestamptz AS current_deletion
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?
      FOR UPDATE OF f")

(defmethod delete-object :file
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at ::queued] :as params}]
  (when-let [file (db/exec-one! conn [sql:lock-file-for-deletion deleted-at id])]
    (when (or (not queued) (:current-deletion file))
      (delete-file cfg file (earliest-deadline deleted-at (:project-deleted-at file)) params))))

(def ^:private sql:lock-project-files
  "SELECT id, deleted_at, is_shared
     FROM file
    WHERE project_id = ?
    ORDER BY id
      FOR UPDATE")

(defmethod delete-object :project
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at ::queued]}]
  (when-let [project (db/get* conn :project {:id id}
                              {::db/remove-deleted false
                               ::sql/columns [:deleted-at]
                               ::sql/for-update true})]
    (when (or (not queued) (= deleted-at (:deleted-at project)))
      (let [deleted-at (earliest-deadline deleted-at (:deleted-at project))]
        (l/trc :obj "project" :id (str id)
               :deleted-at (ct/format-inst deleted-at))

        (db/update! conn :project
                    {:deleted-at deleted-at}
                    {:id id}
                    {::db/return-keys false})

        (let [files (db/exec! conn [sql:lock-project-files id])]
          ;; Avoid absorbing into files that die in the same cascade.
          (binding [*deleting-file-ids* (into #{} d/xf:map-id files)]
            (doseq [file files]
              (delete-file cfg file deleted-at nil))))

        (record :projects id)))))

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
                              {::sql/columns [:id]
                               ::sql/order-by [:id]})]
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
   [:purge {:optional true} :boolean]
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
          ;; Flat team ids, so a consumer can key on them without
          ;; joining production tables.
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
   (fn [context params]
     ;; A queued file or project job must match the current mark. Direct
     ;; SREPL calls and real ancestor cascades can start from live rows.
     (db/tx-run! cfg execute-delete-object (assoc params ::queued (some? context))))
   ::jobs/decoder   (sm/decoder schema:delete-object-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:delete-object-params)})
