;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-gc
  "A maintenance task that is responsible of: purge unused file media,
  clean unused object thumbnails and remove old file thumbnails.  The
  file is eligible to be garbage collected after some period of
  inactivity (the default threshold is 72h)."
  (:require
   [app.binfile.cleaner :as bfl]
   [app.binfile.common :as bfc]
   [app.common.files.helpers :as cfh]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.features.file-snapshots :as fsnap]
   [app.storage :as sto]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(declare get-file)

(def ^:private sql:mark-file-media-object-deleted
  "UPDATE file_media_object
      SET deleted_at = now()
    WHERE file_id = ? AND id != ALL(?::uuid[])
   RETURNING id")

(def xf:collect-used-media
  (comp
   (map :data)
   (mapcat cfh/collect-used-media)))

(defn- clean-file-media!
  "Performs the garbage collection of file media objects."
  [{:keys [::db/conn] :as cfg} {:keys [id] :as file}]
  (let [used-media
        (fsnap/reduce-snapshots cfg id xf:collect-used-media conj #{})

        used-media
        (into used-media xf:collect-used-media [file])

        used-media
        (db/create-array conn "uuid" used-media)

        unused-media
        (->> (db/exec! conn [sql:mark-file-media-object-deleted id used-media])
             (into #{} (map :id)))]

    (l/dbg :hint "clean" :rel "file-media-object" :file-id (str id) :total (count unused-media))

    (doseq [id unused-media]
      (l/trc :hint "mark deleted"
             :rel "file-media-object"
             :id (str id)
             :file-id (str id)))

    file))

(def ^:private sql:mark-file-object-thumbnails-deleted
  "UPDATE file_tagged_object_thumbnail
      SET deleted_at = now()
    WHERE file_id = ? AND object_id != ALL(?::text[])
   RETURNING object_id")

(defn- clean-file-object-thumbnails!
  [{:keys [::db/conn]} {:keys [data] :as file}]
  (let [file-id (:id file)
        using   (->> (vals (:pages-index data))
                     (into #{} (comp
                                (mapcat (fn [{:keys [id objects]}]
                                          (->> (ctt/get-frames objects)
                                               (map #(assoc % :page-id id)))))
                                (mapcat (fn [{:keys [id page-id]}]
                                          (list
                                           (thc/fmt-object-id file-id page-id id "frame")
                                           (thc/fmt-object-id file-id page-id id "component")))))))

        ids    (db/create-array conn "uuid" using)
        unused (->> (db/exec! conn [sql:mark-file-object-thumbnails-deleted file-id ids])
                    (into #{} (map :object-id)))]

    (l/dbg :hint "clean" :rel "file-object-thumbnail" :file-id (str file-id) :total (count unused))

    (doseq [object-id unused]
      (l/trc :hint "mark deleted"
             :rel "file-tagged-object-thumbnail"
             :object-id object-id
             :file-id (str file-id)))

    file))

(def ^:private sql:mark-file-thumbnails-deleted
  "UPDATE file_thumbnail
      SET deleted_at = now()
    WHERE file_id = ? AND revn < ?
   RETURNING revn")

(defn- clean-file-thumbnails!
  [{:keys [::db/conn]} {:keys [id revn] :as file}]
  (let [unused (->> (db/exec! conn [sql:mark-file-thumbnails-deleted id revn])
                    (into #{} (map :revn)))]

    (l/dbg :hint "clean" :rel "file-thumbnail" :file-id (str id) :total (count unused))

    (doseq [revn unused]
      (l/trc :hint "mark deleted"
             :rel "file-thumbnail"
             :revn revn
             :file-id (str id)))

    file))

(def ^:private sql:get-files-for-library
  "SELECT f.id
     FROM file AS f
     LEFT JOIN file_library_rel AS fl ON (fl.file_id = f.id)
    WHERE fl.library_file_id = ?
      AND f.deleted_at IS null
    ORDER BY f.modified_at ASC")

(defn- get-used-components
  "Given a file and a set of components marked for deletion, return a
  filtered set of component ids that are still un use"
  [components library-id {:keys [data]}]
  (filter #(ctf/used-in? data library-id % :component) components))

(defn- clean-deleted-components!
  "Performs the garbage collection of unreferenced deleted components."
  [{:keys [::db/conn] :as cfg} {:keys [data] :as file}]
  (let [file-id (:id file)

        deleted-components
        (ctkl/deleted-components-seq data)

        file-xform
        (mapcat (partial get-used-components deleted-components file-id))

        library-xform
        (comp
         (map :id)
         (map #(bfc/get-file cfg % :realize? true :read-only? true))
         file-xform)

        used-remote
        (->> (db/plan conn [sql:get-files-for-library file-id] {:fetch-size 1})
             (transduce library-xform conj #{}))

        used-local
        (into #{} file-xform [file])

        unused
        (transduce bfc/xf-map-id disj
                   (into #{} bfc/xf-map-id deleted-components)
                   (concat used-remote used-local))

        file
        (update file :data
                (fn [data]
                  (reduce (fn [data id]
                            (l/trc :hint "delete component"
                                   :component-id (str id)
                                   :file-id (str file-id))
                            (ctkl/delete-component data id))
                          data
                          unused)))]

    (l/dbg :hint "clean" :rel "components" :file-id (str file-id) :total (count unused))
    file))

(def ^:private sql:mark-deleted-data-fragments
  "UPDATE file_data_fragment
      SET deleted_at = now()
    WHERE file_id = ?
      AND id != ALL(?::uuid[])
      AND deleted_at IS NULL
   RETURNING id")

(def ^:private xf:collect-pointers
  (comp (map :data)
        (mapcat feat.fdata/get-used-pointer-ids)))

(defn- clean-fragments!
  [{:keys [::db/conn]} {:keys [id] :as file}]
  (let [used   (into #{} xf:collect-pointers [file])

        unused (->> (db/exec! conn [sql:mark-deleted-data-fragments id
                                    (db/create-array conn "uuid" used)])
                    (into #{} bfc/xf-map-id))]

    (l/dbg :hint "clean" :rel "file-data-fragment" :file-id (str id) :total (count unused))
    (doseq [id unused]
      (l/trc :hint "mark deleted"
             :rel "file-data-fragment"
             :id (str id)
             :file-id (str id)))

    file))

(defn- clean-media!
  [cfg file]
  (let [file (->> file
                  (clean-deleted-components! cfg)
                  (clean-file-media! cfg)
                  (clean-file-thumbnails! cfg)
                  (clean-file-object-thumbnails! cfg))]
    (cfv/validate-file-schema! file)
    file))

(defn get-file
  [cfg {:keys [file-id revn]}]
  (let [file (bfc/get-file cfg file-id
                           :realize? true
                           :skip-locked? true
                           :lock-for-update? true)]

    ;; We should ensure that the scheduled file and the procesing file
    ;; has not changed since schedule, for this reason we check the
    ;; revn from props with the revn from retrieved file from database
    (when (= revn (:revn file))
      file)))

(defn- process-file!
  [cfg {:keys [file-id] :as props}]
  (if-let [file (get-file cfg props)]
    (let [file (->> file
                    (bfc/decode-file cfg)
                    (bfl/clean-file)
                    (clean-media! cfg)
                    (clean-fragments! cfg))
          file (assoc file :has-media-trimmed true)]
      (bfc/update-file! cfg file)
      true)

    (do
      (l/dbg :hint "skip cleaning, criteria does not match" :file-id (str file-id))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (try
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (db/tx-run! (fn [{:keys [::db/conn] :as cfg}]
                        (let [cfg        (update cfg ::sto/storage sto/configure conn)
                              processed? (process-file! cfg props)]
                          (when (and processed? (contains? cf/flags :tiered-file-data-storage))
                            (wrk/submit! (-> cfg
                                             (assoc ::wrk/task :offload-file-data)
                                             (assoc ::wrk/params props)
                                             (assoc ::wrk/priority 10)
                                             (assoc ::wrk/delay 1000))))
                          processed?))))
      (catch Throwable cause
        (l/err :hint "error on cleaning file"
               :file-id (str (:file-id props))
               :cause cause)))))
