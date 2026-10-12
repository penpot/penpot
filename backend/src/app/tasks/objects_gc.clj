;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.tasks.objects-gc
  "A maintenance task that performs a general purpose garbage collection
  of deleted or unreachable objects."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.features.fdata :as fdata]
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
                (fn [{:keys [::db/conn ::timestamp ::sto/storage] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-profiles ids timestamp])
                         (reduce (fn [total {:keys [id photo-id deleted-at]}]
                                   (l/trc :obj "profile" :id (str id))

                                   ;; Mark as deleted the storage object
                                   (some->> photo-id (sto/touch-object! storage))

                                   ;; Cascade soft-delete to owned teams, projects, files, etc.
                                   ;; with the deletion date of the profile, not the
                                   ;; timestamp of the run.
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-profiles-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-profiles timestamp]
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
                (fn [{:keys [::db/conn ::timestamp ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-teams ids timestamp])
                         (reduce (fn [total {:keys [id photo-id deleted-at]}]
                                   (l/trc :obj "team"
                                          :id (str id)
                                          :deleted-at (ct/format-inst deleted-at))

                                   ;; Mark as deleted the storage object
                                   (some->> photo-id (sto/touch-object! storage))

                                   ;; And finally, permanently delete the team.
                                   (let [affected (-> (db/delete! conn :team {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "team"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-teams
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-teams-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-teams timestamp]
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
                (fn [{:keys [::db/conn ::timestamp ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-fonts ids timestamp])
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-fonts-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-fonts timestamp]
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
                (fn [{:keys [::db/conn ::timestamp]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-projects ids timestamp])
                         (reduce (fn [total {:keys [id team-id deleted-at]}]
                                   (l/trc :obj "project"
                                          :id (str id)
                                          :team-id (str team-id)
                                          :deleted-at (ct/format-inst deleted-at))

                                   (let [affected (-> (db/delete! conn :project {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
    (catch Throwable cause
      (l/err :obj "project"
             :hint "error on chunk deletion, skipping it"
             :chunk ids
             :cause cause)
      0)))

(defn- delete-projects
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-projects-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-projects timestamp]
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
                (fn [{:keys [::db/conn ::timestamp]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-files ids timestamp])
                         (reduce (fn [total {:keys [id deleted-at project-id]}]
                                   (l/trc :obj "file"
                                          :id (str id)
                                          :project-id (str project-id)
                                          :deleted-at (ct/format-inst deleted-at))

                                   (let [affected (-> (db/delete! conn :file {:id id})
                                                      (db/get-update-count))]
                                     (+ total affected)))
                                 0)))))
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-files-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-files timestamp]
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
                (fn [{:keys [::db/conn ::timestamp ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [file-ids (db/create-array conn "uuid" (map :file-id rows))
                        revns    (db/create-array conn "int8" (map :revn rows))]
                    (->> (db/exec! conn [sql:lock-file-thumbnails file-ids revns timestamp])
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-file-thumbnails-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map (fn [row] {:file-id (:file-id row) :revn (:revn row)}))
                                   (partition-all chunk-size))
                             rf 0
                             (db/plan conn [sql:get-file-thumbnails timestamp]
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
                (fn [{:keys [::db/conn ::timestamp ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [file-ids   (db/create-array conn "uuid" (map :file-id rows))
                        object-ids (db/create-array conn "text" (map :object-id rows))]
                    (->> (db/exec! conn [sql:lock-file-object-thumbnails file-ids object-ids timestamp])
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-file-object-thumbnails-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map (fn [row] {:file-id (:file-id row) :object-id (:object-id row)}))
                                   (partition-all chunk-size))
                             rf 0
                             (db/plan conn [sql:get-file-object-thumbnails timestamp]
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
                (fn [{:keys [::db/conn ::timestamp ::sto/storage]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-file-media-objects ids timestamp])
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-file-media-objects-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-file-media-objects timestamp]
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
                (fn [{:keys [::db/conn ::timestamp] :as cfg}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [file-ids (db/create-array conn "uuid" (map :file-id rows))
                        ids      (db/create-array conn "uuid" (map :id rows))]
                    (->> (db/exec! conn [sql:lock-file-data file-ids ids timestamp])
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-file-data-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map (fn [row] {:file-id (:file-id row) :id (:id row)}))
                                   (partition-all chunk-size))
                             rf 0
                             (db/plan conn [sql:get-file-data timestamp]
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
                (fn [{:keys [::db/conn ::timestamp]}]
                  (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                  (let [ids (db/create-array conn "uuid" ids)]
                    (->> (db/exec! conn [sql:lock-file-change ids timestamp])
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
  [{:keys [::timestamp ::chunk-size] :as cfg}]
  (let [rf (fn ([total] total)
             ([total chunk] (+ total (delete-file-changes-chunk cfg chunk))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce (comp (map :id) (partition-all chunk-size)) rf 0
                             (db/plan conn [sql:get-file-change timestamp]
                                      {:fetch-size 5}))))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (assoc v ::chunk-size 100)})

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props]}]
    (let [cfg   (assoc cfg ::timestamp (ct/now))
          cfg   (assoc cfg ::chunk-size (or (:chunk-size props)
                                            (::chunk-size cfg)))
          total (+ (delete-profiles cfg)
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
