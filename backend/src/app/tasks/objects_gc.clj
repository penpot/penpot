;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.objects-gc
  "A maintenance task that performs a general purpose garbage collection
  of deleted or unreachable objects."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def ^:private sql:get-profiles
  "SELECT id, photo_id FROM profile
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-profiles!
  [{:keys [::db/conn ::timestamp ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-profiles timestamp chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id photo-id]}]
                 (l/trc :obj "profile" :id (str id))

                 ;; Mark as deleted the storage object
                 (some->> photo-id (sto/touch-object! storage))

                 (let [affected (-> (db/delete! conn :profile {:id id})
                                    (db/get-update-count))]
                   (+ total affected)))
               0)))

(def ^:private sql:get-teams
  "SELECT deleted_at, id, photo_id FROM team
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-teams!
  [{:keys [::db/conn ::timestamp ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-teams timestamp chunk-size] {:fetch-size 5})
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
               0)))

(def ^:private sql:get-fonts
  "SELECT id, team_id, deleted_at, woff1_file_id, woff2_file_id, otf_file_id, ttf_file_id
     FROM team_font_variant
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-fonts!
  [{:keys [::db/conn ::timestamp ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-fonts timestamp chunk-size] {:fetch-size 5})
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
               0)))

(def ^:private sql:get-projects
  "SELECT id, deleted_at, team_id
     FROM project
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-projects!
  [{:keys [::db/conn ::timestamp ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-projects timestamp chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id team-id deleted-at]}]
                 (l/trc :obj "project"
                        :id (str id)
                        :team-id (str team-id)
                        :deleted-at (ct/format-inst deleted-at))

                 (let [affected (-> (db/delete! conn :project {:id id})
                                    (db/get-update-count))]
                   (+ total affected)))
               0)))

(def ^:private sql:get-files
  "SELECT f.id,
          f.deleted_at,
          f.project_id
     FROM file AS f
    WHERE f.deleted_at IS NOT NULL
      AND f.deleted_at <= ?
    ORDER BY f.deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-files!
  [{:keys [::db/conn ::timestamp ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-files timestamp chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id deleted-at project-id] :as file}]
                 (l/trc :obj "file"
                        :id (str id)
                        :project-id (str project-id)
                        :deleted-at (ct/format-inst deleted-at))

                 (let [affected (-> (db/delete! conn :file {:id id})
                                    (db/get-update-count))]
                   (+ total affected)))
               0)))

(def ^:private sql:get-file-thumbnails
  "SELECT file_id, revn, media_id, deleted_at
     FROM file_thumbnail
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn delete-file-thumbnails!
  [{:keys [::db/conn ::timestamp ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-file-thumbnails timestamp chunk-size] {:fetch-size 5})
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
               0)))

(def ^:private sql:get-file-object-thumbnails
  "SELECT file_id, object_id, media_id, deleted_at
     FROM file_tagged_object_thumbnail
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn delete-file-object-thumbnails!
  [{:keys [::db/conn ::timestamp ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-file-object-thumbnails timestamp chunk-size] {:fetch-size 5})
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
               0)))

(def ^:private sql:get-file-media-objects
  "SELECT id, file_id, media_id, thumbnail_id, deleted_at
     FROM file_media_object
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-media-objects!
  [{:keys [::db/conn ::timestamp ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-file-media-objects timestamp chunk-size] {:fetch-size 5})
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
               0)))

(def ^:private sql:get-file-data
  "SELECT file_id, id, type, deleted_at, metadata, backend
     FROM file_data
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-data!
  [{:keys [::db/conn ::timestamp ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-file-data timestamp chunk-size] {:fetch-size 5})
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
               0)))

(def ^:private sql:get-file-change
  "SELECT id, file_id, deleted_at
     FROM file_change
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-changes!
  [{:keys [::db/conn ::timestamp ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-file-change timestamp chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id file-id deleted-at] :as xlog}]
                 (l/trc :obj "file-change"
                        :id (str id)
                        :file-id (str file-id)
                        :deleted-at (ct/format-inst deleted-at))

                 (let [affected (-> (db/delete! conn :file-change {:id id})
                                    (db/get-update-count))]
                   (+ total affected)))
               0)))

(def ^:private deletion-proc-vars
  [#'delete-profiles!
   #'delete-file-media-objects!
   #'delete-file-object-thumbnails!
   #'delete-file-thumbnails!
   #'delete-file-data!
   #'delete-file-changes!
   #'delete-files!
   #'delete-projects!
   #'delete-fonts!
   #'delete-teams!])

(defn- execute-proc!
  "A generic function that executes the specified proc iterativelly
  until 0 results is returned"
  [cfg proc-fn]
  (loop [total 0]
    (let [result (db/tx-run! cfg
                             (fn [{:keys [::db/conn] :as cfg}]
                               (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                               (proc-fn cfg)))]
      (if (pos? result)
        (recur (long (+ total result)))
        total))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (assoc v ::chunk-size 100)})

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (let [cfg (assoc cfg ::timestamp (ct/now))]
      (loop [procs (map deref deletion-proc-vars)
             total 0]
        (if-let [proc-fn (first procs)]
          (let [result (execute-proc! cfg proc-fn)]
            (recur (rest procs)
                   (long (+ total result))))
          {:processed total})))))
