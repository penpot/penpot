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
   [app.db :as db]
   [app.storage :as sto]
   [app.util.time :as dt]
   [integrant.core :as ig]))

(def ^:private sql:get-profiles
  "SELECT id, photo_id FROM profile
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-profiles!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-profiles deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id photo-id]}]
                 (l/trc :hint "permanently delete" :rel "profile" :id (str id))

                 ;; Mark as deleted the storage object
                 (some->> photo-id (sto/touch-object! storage))

                 (db/delete! conn :profile {:id id})

                 (inc total))
               0)))

(def ^:private sql:get-teams
  "SELECT deleted_at, id, photo_id FROM team
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-teams!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-teams deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id photo-id deleted-at]}]
                 (l/trc :hint "permanently delete"
                        :rel "team"
                        :id (str id)
                        :deleted-at (dt/format-instant deleted-at))

                 ;; Mark as deleted the storage object
                 (some->> photo-id (sto/touch-object! storage))

                 ;; And finally, permanently delete the team.
                 (db/delete! conn :team {:id id})

                 (inc total))
               0)))

(def ^:private sql:get-fonts
  "SELECT id, team_id, deleted_at, woff1_file_id, woff2_file_id, otf_file_id, ttf_file_id
     FROM team_font_variant
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-fonts!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-fonts deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id team-id deleted-at] :as font}]
                 (l/trc :hint "permanently delete"
                        :rel "team-font-variant"
                        :id (str id)
                        :team-id (str team-id)
                        :deleted-at (dt/format-instant deleted-at))

                 ;; Mark as deleted the all related storage objects
                 (some->> (:woff1-file-id font) (sto/touch-object! storage))
                 (some->> (:woff2-file-id font) (sto/touch-object! storage))
                 (some->> (:otf-file-id font)   (sto/touch-object! storage))
                 (some->> (:ttf-file-id font)   (sto/touch-object! storage))

                 ;; And finally, permanently delete the team font variant
                 (db/delete! conn :team-font-variant {:id id})

                 (inc total))
               0)))

(def ^:private sql:get-projects
  "SELECT id, deleted_at, team_id
     FROM project
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-projects!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-projects deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id team-id deleted-at]}]
                 (l/trc :hint "permanently delete"
                        :rel "project"
                        :id (str id)
                        :team-id (str team-id)
                        :deleted-at (dt/format-instant deleted-at))

                 ;; And finally, permanently delete the project.
                 (db/delete! conn :project {:id id})

                 (inc total))
               0)))

(def ^:private sql:get-files
  "SELECT f.id,
          f.deleted_at,
          f.project_id
     FROM file AS f
    WHERE f.deleted_at IS NOT NULL
      AND f.deleted_at < now() + ?::interval
    ORDER BY f.deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-files!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-files deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id deleted-at project-id] :as file}]
                 (l/trc :hint "permanently delete"
                        :rel "file"
                        :id (str id)
                        :project-id (str project-id)
                        :deleted-at (dt/format-instant deleted-at))

                 ;; Mark a possible file data as deleted
                 (db/update! conn :file-data
                             {:deleted-at deleted-at}
                             {:file-id id :id id :type "main"}
                             {::db/return-keys false})

                 ;; And finally, permanently delete the file.
                 (db/delete! conn :file {:id id})

                 (inc total))
               0)))

(def ^:private sql:get-file-thumbnails
  "SELECT file_id, revn, media_id, deleted_at
     FROM file_thumbnail
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn delete-file-thumbnails!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-file-thumbnails deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [file-id revn media-id deleted-at]}]
                 (l/trc :hint "permanently delete"
                        :rel "file-thumbnail"
                        :file-id (str file-id)
                        :revn revn
                        :deleted-at (dt/format-instant deleted-at))

                 ;; Mark as deleted the storage object
                 (some->> media-id (sto/touch-object! storage))

                 ;; And finally, permanently delete the object
                 (db/delete! conn :file-thumbnail {:file-id file-id :revn revn})

                 (inc total))
               0)))

(def ^:private sql:get-file-object-thumbnails
  "SELECT file_id, object_id, media_id, deleted_at
     FROM file_tagged_object_thumbnail
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn delete-file-object-thumbnails!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-file-object-thumbnails deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [file-id object-id media-id deleted-at]}]
                 (l/trc :hint "permanently delete"
                        :rel "file-tagged-object-thumbnail"
                        :file-id (str file-id)
                        :object-id object-id
                        :deleted-at (dt/format-instant deleted-at))

                 ;; Mark as deleted the storage object
                 (some->> media-id (sto/touch-object! storage))

                 ;; And finally, permanently delete the object
                 (db/delete! conn :file-tagged-object-thumbnail {:file-id file-id :object-id object-id})

                 (inc total))
               0)))

;; (def ^:private sql:get-file-data
;;   "SELECT fd.file_id,
;;           fd.id,
;;           fd.deleted_at,
;;           fd.type,
;;           fd.backend,
;;           fd.metadata
;;      FROM file_data AS fd
;;     WHERE fd.deleted_at IS NOT NULL
;;       AND fd.deleted_at < now() + ?::interval
;;     ORDER BY fd.deleted_at ASC
;;     LIMIT ?
;;       FOR UPDATE
;;      SKIP LOCKED")

;; (defn- delete-file-data!
;;   [{:keys [::db/conn ::sto/storage ::deletion-threshold ::chunk-size] :as cfg}]
;;   (->> (db/plan conn [sql:get-file-data deletion-threshold chunk-size] {:fetch-size 5})
;;        (reduce (fn [total {:keys [file-id id deleted-at type]}]
;;                  (l/trc :hint "permanently delete"
;;                         :rel "file-data"
;;                         :id (str id)
;;                         :type type
;;                         :file-id (str file-id)
;;                         :deleted-at (dt/format-instant deleted-at))

;;                  ;; FIXME: implement backend based deletion process
;;                  ;; (some->> data-ref-id (sto/touch-object! storage))
;;                  (db/delete! conn :file-data {:file-id file-id :id id})

;;                  (inc total))
;;                0)))

(def ^:private sql:get-file-media-objects
  "SELECT id, file_id, media_id, thumbnail_id, deleted_at
     FROM file_media_object
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-media-objects!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size ::sto/storage] :as cfg}]
  (->> (db/plan conn [sql:get-file-media-objects deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id file-id deleted-at] :as fmo}]
                 (l/trc :hint "permanently delete"
                        :rel "file-media-object"
                        :id (str id)
                        :file-id (str file-id)
                        :deleted-at (dt/format-instant deleted-at))

                 ;; Mark as deleted the all related storage objects
                 (some->> (:media-id fmo) (sto/touch-object! storage))
                 (some->> (:thumbnail-id fmo) (sto/touch-object! storage))

                 (db/delete! conn :file-media-object {:id id})

                 (inc total))
               0)))

(def ^:private sql:get-file-data-fragments
  "SELECT file_id, id, deleted_at
     FROM file_data_fragment
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-data-fragments!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-file-data-fragments deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [file-id id deleted-at]}]
                 (l/trc :hint "permanently delete"
                        :rel "file-data-fragment"
                        :id (str id)
                        :file-id (str file-id)
                        :deleted-at (dt/format-instant deleted-at))

                 (db/delete! conn :file-data-fragment {:file-id file-id :id id})
                 (db/delete! conn :file-data {:file-id file-id :id id :type "fragment"})

                 (inc total))
               0)))

(def ^:private sql:get-file-change
  "SELECT id, file_id, deleted_at
     FROM file_change
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() + ?::interval
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- delete-file-changes!
  [{:keys [::db/conn ::deletion-threshold ::chunk-size] :as cfg}]
  (->> (db/plan conn [sql:get-file-change deletion-threshold chunk-size] {:fetch-size 5})
       (reduce (fn [total {:keys [id file-id deleted-at] :as xlog}]
                 (l/trc :hint "permanently delete"
                        :rel "file-change"
                        :id (str id)
                        :file-id (str file-id)
                        :deleted-at (dt/format-instant deleted-at))

                 (db/delete! conn :file-data
                             {:file-id file-id :id id :type "snapshot"}
                             {::db/return-keys false})

                 (db/delete! conn :file-change {:id id})

                 (inc total))
               0)))

(def ^:private deletion-proc-vars
  [#'delete-profiles!
   #'delete-file-media-objects!
   #'delete-file-object-thumbnails!

   #'delete-file-thumbnails!
   #'delete-file-changes!
   #'delete-file-data-fragments!
   #'delete-files!
   #'delete-projects!
   #'delete-fonts!
   #'delete-teams!])

(defn- execute-proc!
  "A generic function that executes the specified proc iterativelly
  until 0 results is returned"
  [cfg proc-fn]
  (loop [total 0]
    (let [result (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                                   (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
                                   (proc-fn cfg)))]
      (if (pos? result)
        (recur (+ total result))
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
  (fn [{:keys [props] :as task}]
    (let [threshold (dt/duration (get props :deletion-threshold 0))
          cfg       (assoc cfg ::deletion-threshold (db/interval threshold))]
      (loop [procs (map deref deletion-proc-vars)
             total 0]
        (if-let [proc-fn (first procs)]
          (let [result (execute-proc! cfg proc-fn)]
            (recur (rest procs)
                   (+ total result)))
          (do
            (l/inf :hint "task finished" :deleted total)
            {:processed total}))))))
