;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.objects-gc
  "A maintenance task that performs a general purpose garbage collection
  of deleted or unreachable objects."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.storage :as sto]
   [app.tasks.delete-object :as dobj]
   [integrant.core :as ig]))

;; Each proc below streams its pending keys with `db/plan`, a server-side
;; cursor, inside a read-only transaction of its own, and deletes them chunk
;; by chunk, each chunk in a transaction of its own, unrelated to the plan
;; one. A chunk that fails is rolled back on its own, reported with `l/err`
;; and skipped, so it takes down its own work and nothing else: the run keeps
;; going and the next run retries the skipped rows.
;;
;; A plan row is a view over the open result set, valid only while the
;; reduction is running, so the key is read inside the reducing function:
;; buffering the row itself yields nil columns.
;;
;; Every proc beats its job (`jobs/heartbeat`) after each processed chunk,
;; through the pool and never through the chunk transaction, so a long backlog
;; in a single proc cannot outrun the orphan lease.

(defn- submit-hard-delete-event
  "Emits one hard-delete audit event for the batch of rows just purged
  by this task, so a projection of the audit stream can drop its local
  rows. Each GC section purges in its own chunk transactions, so each
  chunk carries only its own entity key (`:team-ids`, `:project-ids`
  or `:file-ids`); a single event spanning sections could never share
  their transactions. Runs inside the caller transaction, next to the
  deletes: if the purge aborts, the event aborts with it."
  [cfg ids-key ids]
  (when (contains? cf/flags :audit-log)
    (audit/submit cfg
                  {:name "hard-delete-objects"
                   :type "command"
                   :props {ids-key (vec ids)}
                   :context {:triggered-by "objects-gc"}})))

(def ^:private sql:get-upload-sessions
  "SELECT us.id
     FROM upload_session AS us
    WHERE (us.deleted_at IS NOT NULL
           AND us.deleted_at <= ?)
       OR (us.deleted_at IS NULL
           AND us.created_at <= ?)
       OR EXISTS (SELECT 1
                    FROM profile AS p
                   WHERE p.id = us.profile_id
                     AND p.deleted_at IS NOT NULL
                     AND p.deleted_at <= ?)
    ORDER BY us.created_at ASC")

(def ^:private sql:lock-upload-sessions
  "SELECT us.id
     FROM upload_session AS us
    WHERE us.id = ANY(?::uuid[])
      AND ((us.deleted_at IS NOT NULL
            AND us.deleted_at <= ?)
        OR (us.deleted_at IS NULL
            AND us.created_at <= ?)
        OR EXISTS (SELECT 1
                     FROM profile AS p
                    WHERE p.id = us.profile_id
                      AND p.deleted_at IS NOT NULL
                      AND p.deleted_at <= ?))
      FOR UPDATE OF us
     SKIP LOCKED")

(def ^:private sql:delete-session-chunks
  "DELETE FROM upload_session_chunk
    WHERE session_id = ?
    RETURNING object_id")

(defn- delete-upload-sessions-chunk
  "Purges one chunk of consumed upload sessions (marked by assemble-chunks),
  stalled sessions (never assembled within max-age) and sessions owned by
  profiles pending purge. Referenced storage objects are touched so the
  storage GC reclaims them with its usual delay; chunk mappings are removed
  before the session row (NO ACTION foreign keys)."
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage]}]
                  (let [stalled-threshold (ct/minus threshold {:hours 1})
                        ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-upload-sessions ids threshold stalled-threshold threshold])
                         (reduce (fn [total {:keys [id]}]
                                   (l/trc :obj "upload-session" :id (str id))

                                   ;; Remove the chunk mappings, marking as touched all
                                   ;; related storage objects in a single round-trip.
                                   (doseq [{:keys [object-id]} (db/exec! conn [sql:delete-session-chunks id])]
                                     (some->> object-id (sto/touch-object! storage)))

                                   (let [affected (-> (db/delete! conn :upload-session {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "upload-session"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-upload-sessions
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [stalled-threshold (ct/minus threshold {:hours 1})
        rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-upload-sessions-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-upload-sessions threshold stalled-threshold threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-profiles
  "SELECT id FROM profile
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-profiles
  "SELECT id, photo_id, deleted_at FROM profile
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-profiles-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-profiles ids threshold])
                         (reduce (fn [total {:keys [id photo-id deleted-at]}]
                                   (l/trc :obj "profile" :id (str id))

                                   ;; Mark as deleted the storage object
                                   (some->> photo-id (sto/touch-object! storage))

                                   ;; Cascade soft-delete to owned teams, projects, files, etc.
                                   ;; with the deletion date of the profile, not the
                                   ;; date of the run.
                                   (dobj/delete-object cfg {:object :profile
                                                            :id id
                                                            :deleted-at deleted-at})

                                   (let [affected (-> (db/delete! conn :profile {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "profile"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-profiles
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-profiles-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-profiles threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-teams
  "SELECT id FROM team
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-teams
  "SELECT deleted_at, id, photo_id FROM team
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-teams-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)
                        [total deleted-ids]
                        (->> (db/exec! conn [sql:lock-teams ids threshold])
                             (reduce (fn [[total deleted-ids] {:keys [id photo-id deleted-at]}]
                                       (l/trc :obj "team"
                                              :id (str id)
                                              :deleted-at (ct/format-inst deleted-at))

                                       ;; Mark as deleted the storage object
                                       (some->> photo-id (sto/touch-object! storage))

                                       ;; And finally, permanently delete the team.
                                       (let [affected (-> (db/delete! conn :team {:id id})
                                                          (db/get-update-count))]
                                         [(+ total affected)
                                          (cond-> deleted-ids (pos? affected) (conj id))]))
                                     [0 []]))]
                    (when (seq deleted-ids)
                      (submit-hard-delete-event cfg :team-ids deleted-ids))
                    total)))
    (catch Throwable cause
      (l/err :obj "team"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-teams
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-teams-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-teams threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-fonts
  "SELECT id FROM team_font_variant
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-fonts
  "SELECT id, team_id, deleted_at, woff1_file_id, woff2_file_id, otf_file_id, ttf_file_id
     FROM team_font_variant
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-fonts-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-fonts ids threshold])
                         (reduce (fn [total {:keys [id team-id deleted-at] :as font}]
                                   (l/trc :obj "font-variant"
                                          :id (str id)
                                          :team-id (str team-id)
                                          :deleted-at (ct/format-inst deleted-at))

                                   ;; Mark as deleted the all related storage objects
                                   (some->> (:woff1-file-id font) (sto/touch-object! storage))
                                   (some->> (:woff2-file-id font) (sto/touch-object! storage))
                                   (some->> (:otf-file-id font)   (sto/touch-object! storage))
                                   (some->> (:ttf-file-id font)   (sto/touch-object! storage))

                                   (let [affected (-> (db/delete! conn :team-font-variant {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "font-variant"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-fonts
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-fonts-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-fonts threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-projects
  "SELECT id FROM project
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-projects
  "SELECT id, deleted_at, team_id FROM project
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-projects-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)
                        [total deleted-ids]
                        (->> (db/exec! conn [sql:lock-projects ids threshold])
                             (reduce (fn [[total deleted-ids] {:keys [id team-id deleted-at]}]
                                       (l/trc :obj "project"
                                              :id (str id)
                                              :team-id (str team-id)
                                              :deleted-at (ct/format-inst deleted-at))

                                       (let [affected (-> (db/delete! conn :project {:id id})
                                                          (db/get-update-count))]
                                         [(+ total affected)
                                          (cond-> deleted-ids (pos? affected) (conj id))]))
                                     [0 []]))]
                    (when (seq deleted-ids)
                      (submit-hard-delete-event cfg :project-ids deleted-ids))
                    total)))
    (catch Throwable cause
      (l/err :obj "project"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-projects
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-projects-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-projects threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-files
  "SELECT id FROM file
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-files
  "SELECT id, deleted_at, project_id FROM file
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-files-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)
                        [total deleted-ids]
                        (->> (db/exec! conn [sql:lock-files ids threshold])
                             (reduce (fn [[total deleted-ids] {:keys [id deleted-at project-id]}]
                                       (l/trc :obj "file"
                                              :id (str id)
                                              :project-id (str project-id)
                                              :deleted-at (ct/format-inst deleted-at))

                                       (let [affected (-> (db/delete! conn :file {:id id})
                                                          (db/get-update-count))]
                                         [(+ total affected)
                                          (cond-> deleted-ids (pos? affected) (conj id))]))
                                     [0 []]))]
                    (when (seq deleted-ids)
                      (submit-hard-delete-event cfg :file-ids deleted-ids))
                    total)))
    (catch Throwable cause
      (l/err :obj "file"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-files
  "Deletes the pending files chunk by chunk.

  A file that cannot be deleted (for example, because a child row was left
  unmarked and its `NO ACTION` foreign key still points at it) aborts the
  transaction of the whole chunk: the chunk is reported and skipped, and the
  next run retries its files."
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-files-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-files threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-file-thumbnails
  "SELECT file_id, revn FROM file_thumbnail
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-file-thumbnails
  "SELECT file_id, revn, media_id, deleted_at
     FROM file_thumbnail
    WHERE (file_id, revn) IN (SELECT file_id, revn FROM unnest(?::uuid[], ?::bigint[]) AS u(file_id, revn))
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-thumbnails-chunk
  [cfg rows]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [file-ids (db/create-array conn "uuid" (map :file-id rows))
                        revns    (db/create-array conn "int8" (map :revn rows))]
                    (->> (db/exec! conn [sql:lock-file-thumbnails file-ids revns threshold])
                         (reduce (fn [total {:keys [file-id revn media-id deleted-at]}]
                                   (l/trc :obj "file-thumbnail"
                                          :file-id (str file-id)
                                          :revn revn
                                          :deleted-at (ct/format-inst deleted-at))

                                   ;; Mark as deleted the storage object
                                   (some->> media-id (sto/touch-object! storage))

                                   (let [affected (-> (db/delete! conn :file-thumbnail {:file-id file-id :revn revn})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "file-thumbnail"
             :hint "error on chunk deletion, skipping it"
             :chunk rows
             :cause cause)
      0)))

(defn- delete-file-thumbnails
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-file-thumbnails-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map (fn [row] {:file-id (:file-id row) :revn (:revn row)}))
                                   (partition-all chunk-size))
                             rf 0
                             (db/plan conn [sql:get-file-thumbnails threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-file-object-thumbnails
  "SELECT file_id, object_id FROM file_tagged_object_thumbnail
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-file-object-thumbnails
  "SELECT file_id, object_id, media_id, deleted_at
     FROM file_tagged_object_thumbnail
    WHERE (file_id, object_id) IN (SELECT file_id, object_id FROM unnest(?::uuid[], ?::text[]) AS u(file_id, object_id))
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-object-thumbnails-chunk
  [cfg rows]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [file-ids   (db/create-array conn "uuid" (map :file-id rows))
                        object-ids (db/create-array conn "text" (map :object-id rows))]
                    (->> (db/exec! conn [sql:lock-file-object-thumbnails file-ids object-ids threshold])
                         (reduce (fn [total {:keys [file-id object-id media-id deleted-at]}]
                                   (l/trc :obj "file-object-thumbnail"
                                          :file-id (str file-id)
                                          :object-id object-id
                                          :deleted-at (ct/format-inst deleted-at))

                                   ;; Mark as deleted the storage object
                                   (some->> media-id (sto/touch-object! storage))

                                   (let [affected (-> (db/delete! conn :file-tagged-object-thumbnail
                                                                  {:file-id file-id :object-id object-id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "file-object-thumbnail"
             :hint "error on chunk deletion, skipping it"
             :chunk rows
             :cause cause)
      0)))

(defn- delete-file-object-thumbnails
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-file-object-thumbnails-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map (fn [row] {:file-id (:file-id row) :object-id (:object-id row)}))
                                   (partition-all chunk-size))
                             rf 0
                             (db/plan conn [sql:get-file-object-thumbnails threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-file-media-objects
  "SELECT id FROM file_media_object
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-file-media-objects
  "SELECT id, file_id, media_id, thumbnail_id, deleted_at
     FROM file_media_object
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-media-objects-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-file-media-objects ids threshold])
                         (reduce (fn [total {:keys [id file-id deleted-at] :as fmo}]
                                   (l/trc :obj "file-media-object"
                                          :id (str id)
                                          :file-id (str file-id)
                                          :deleted-at (ct/format-inst deleted-at))

                                   ;; Mark as deleted the all related storage objects
                                   (some->> (:media-id fmo) (sto/touch-object! storage))
                                   (some->> (:thumbnail-id fmo) (sto/touch-object! storage))

                                   (let [affected (-> (db/delete! conn :file-media-object {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "file-media-object"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-file-media-objects
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-file-media-objects-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-file-media-objects threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-file-data
  "SELECT file_id, id FROM file_data
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-file-data
  "SELECT file_id, id, type, deleted_at, metadata, backend
     FROM file_data
    WHERE (file_id, id) IN (SELECT file_id, id FROM unnest(?::uuid[], ?::uuid[]) AS u(file_id, id))
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-data-chunk
  [cfg rows]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [file-ids (db/create-array conn "uuid" (map :file-id rows))
                        ids      (db/create-array conn "uuid" (map :id rows))]
                    (->> (db/exec! conn [sql:lock-file-data file-ids ids threshold])
                         (reduce (fn [total {:keys [file-id id type deleted-at metadata backend]}]

                                   (some->> metadata
                                            (fdata/decode-metadata)
                                            (fdata/process-metadata cfg))

                                   (l/trc :obj "file-data"
                                          :id (str id)
                                          :file-id (str file-id)
                                          :type type
                                          :backend backend
                                          :deleted-at (ct/format-inst deleted-at))

                                   (let [affected (-> (db/delete! conn :file-data
                                                                  {:file-id file-id
                                                                   :id id
                                                                   :type type})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "file-data"
             :hint "error on chunk deletion, skipping it"
             :chunk rows
             :cause cause)
      0)))

(defn- delete-file-data
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-file-data-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map (fn [row] {:file-id (:file-id row) :id (:id row)}))
                                   (partition-all chunk-size))
                             rf 0
                             (db/plan conn [sql:get-file-data threshold]
                                      {:fetch-size 5}))))))

(def ^:private sql:get-file-change
  "SELECT id FROM file_change
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC")

(def ^:private sql:lock-file-change
  "SELECT id, file_id, deleted_at FROM file_change
    WHERE id = ANY(?::uuid[])
      AND deleted_at IS NOT NULL
      AND deleted_at <= ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-changes-chunk
  [cfg ids]
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::threshold]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-file-change ids threshold])
                         (reduce (fn [total {:keys [id file-id deleted-at]}]
                                   (l/trc :obj "file-change"
                                          :id (str id)
                                          :file-id (str file-id)
                                          :deleted-at (ct/format-inst deleted-at))

                                   (let [affected (-> (db/delete! conn :file-change {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "file-change"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-file-changes
  [{:keys [::threshold ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk]
              (let [deleted (delete-file-changes-chunk cfg chunk)]
                (jobs/heartbeat cfg)
                (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-file-change threshold]
                                      {:fetch-size 5}))))))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/expand-key ::job-def
  [k v]
  {k (assoc v ::chunk-size 100)})

(declare execute-objects-gc)

(def schema:objects-gc-params
  "Optional :skip-delay processes everything immediately, ignoring
  deleted-at schedules (operator/repl escape hatch, mirrors
  storage-gc-touched). Optional :chunk-size overrides the per-proc batch
  size from config."
  [:map {:closed true}
   [:skip-delay {:optional true} :boolean]
   [:chunk-size {:optional true} :int]])

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :objects-gc
   ::jobs/schema    schema:objects-gc-params
   ::jobs/handler
   (fn [_context params]
     (execute-objects-gc cfg params))
   ::jobs/decoder   (sm/decoder schema:objects-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:objects-gc-params)})

(defn execute-objects-gc
  "Plain job handler: garbage collect orphan storage objects."
  ([cfg] (execute-objects-gc cfg {}))
  ([cfg params]
   (let [threshold (if (:skip-delay params)
                     (ct/in-future {:days 3650})
                     (ct/now))
         cfg       (assoc cfg
                          ::threshold threshold
                          ::chunk-size (or (:chunk-size params)
                                           (::chunk-size cfg)))

         ;; NOTE: upload sessions go first: deleting a profile cascades to its
         ;; sessions, which would hit the upload_session_chunk NO ACTION foreign
         ;; key while mappings still exist.
         total     (+ (delete-upload-sessions cfg)
                      (delete-profiles cfg)
                      (delete-file-media-objects cfg)
                      (delete-file-object-thumbnails cfg)
                      (delete-file-thumbnails cfg)
                      (delete-file-data cfg)
                      (delete-file-changes cfg)
                      (delete-files cfg)
                      (delete-projects cfg)
                      (delete-fonts cfg)
                      (delete-teams cfg))]
     {:processed total})))
