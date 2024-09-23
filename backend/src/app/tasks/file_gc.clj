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
   [app.binfile.common :as bfc]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.thumbnails :as thc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private get-file)
(declare ^:private decode-file)
(declare ^:private persist-file!)

(def ^:private sql:get-snapshots
  "SELECT f.file_id AS id,
          f.data,
          f.revn,
          f.version,
          f.features,
          f.data_backend,
          f.data_ref_id
     FROM file_change AS f
    WHERE f.file_id = ?
      AND f.label IS NOT NULL
    ORDER BY f.created_at ASC")

(def ^:private sql:mark-file-media-object-deleted
  "UPDATE file_media_object
      SET deleted_at = now()
    WHERE file_id = ? AND id != ALL(?::uuid[])
   RETURNING id")

(def ^:private xf:collect-used-media
  (comp (map :data) (mapcat bfc/collect-used-media)))

(defn- clean-file-media!
  "Performs the garbage collection of file media objects."
  [{:keys [::db/conn] :as cfg} {:keys [id] :as file}]
  (let [used   (into #{}
                     xf:collect-used-media
                     (cons file
                           (->> (db/cursor conn [sql:get-snapshots id])
                                (map (partial decode-file cfg)))))
        ids    (db/create-array conn "uuid" used)
        unused (->> (db/exec! conn [sql:mark-file-media-object-deleted id ids])
                    (into #{} (map :id)))]

    (l/dbg :hint "clean" :rel "file-media-object" :file-id (str id) :total (count unused))

    (doseq [id unused]
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

        ids    (db/create-array conn "text" using)
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
  "SELECT f.id,
          f.data,
          f.modified_at,
          f.features,
          f.version,
          f.data_backend,
          f.data_ref_id
     FROM file AS f
     LEFT JOIN file_library_rel AS fl ON (fl.file_id = f.id)
    WHERE fl.library_file_id = ?
      AND f.deleted_at IS null
    ORDER BY f.modified_at ASC")

(defn- clean-deleted-components!
  "Performs the garbage collection of unreferenced deleted components."
  [{:keys [::db/conn] :as cfg} {:keys [data] :as file}]
  (let [file-id (:id file)

        get-used-components
        (fn [data components]
          ;; Find which of the components are used in the file.
          (into #{}
                (filter #(ctf/used-in? data file-id % :component))
                components))

        get-unused-components
        (fn [components files]
          ;; Find and return a set of unused components (on all files).
          (reduce (fn [components {:keys [data]}]
                    (if (seq components)
                      (->> (get-used-components data components)
                           (set/difference components))
                      (reduced components)))

                  components
                  files))

        process-fdata
        (fn [data unused]
          (reduce (fn [data id]
                    (l/trc :hint "delete component"
                           :component-id (str id)
                           :file-id (str file-id))
                    (ctkl/delete-component data id))
                  data
                  unused))

        deleted (into #{} (ctkl/deleted-components-seq data))

        unused  (->> (db/cursor conn [sql:get-files-for-library file-id] {:chunk-size 1})
                     (map (partial decode-file cfg))
                     (cons file)
                     (get-unused-components deleted)
                     (mapv :id)
                     (set))

        file    (update file :data process-fdata unused)]


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
        (map blob/decode)
        (mapcat feat.fdata/get-used-pointer-ids)))

(defn- clean-data-fragments!
  [{:keys [::db/conn]} {:keys [id] :as file}]
  (let [used   (into #{} xf:collect-pointers [file])

        unused (let [ids (db/create-array conn "uuid" used)]
                 (->> (db/exec! conn [sql:mark-deleted-data-fragments id ids])
                      (into #{} (map :id))))]

    (l/dbg :hint "clean" :rel "file-data-fragment" :file-id (str id) :total (count unused))
    (doseq [id unused]
      (l/trc :hint "mark deleted"
             :rel "file-data-fragment"
             :id (str id)
             :file-id (str id)))))

(defn- clean-media!
  [cfg file]
  (let [file (->> file
                  (clean-file-media! cfg)
                  (clean-file-thumbnails! cfg)
                  (clean-file-object-thumbnails! cfg)
                  (clean-deleted-components! cfg))]
    (cfv/validate-file-schema! file)
    file))

(def ^:private sql:get-file
  "SELECT f.id,
          f.data,
          f.revn,
          f.version,
          f.features,
          f.modified_at,
          f.data_backend,
          f.data_ref_id
     FROM file AS f
    WHERE f.has_media_trimmed IS false
      AND f.modified_at < now() - ?::interval
      AND f.deleted_at IS NULL
      AND f.id = ?
      FOR UPDATE
     SKIP LOCKED")

(defn- get-file
  [{:keys [::db/conn ::min-age ::file-id]}]
  (->> (db/exec! conn [sql:get-file min-age file-id])
       (first)))

(defn- decode-file
  [cfg {:keys [id] :as file}]
  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)]
    (-> (feat.fdata/resolve-file-data cfg file)
        (update :features db/decode-pgarray #{})
        (update :data blob/decode)
        (update :data feat.fdata/process-pointers deref)
        (update :data feat.fdata/process-objects (partial into {}))
        (update :data assoc :id id)
        (fmg/migrate-file))))

(defn- persist-file!
  [{:keys [::db/conn ::sto/storage] :as cfg} {:keys [id] :as file}]
  (let [file (if (contains? (:features file) "fdata/objects-map")
               (feat.fdata/enable-objects-map file)
               file)

        file (if (contains? (:features file) "fdata/pointer-map")
               (binding [pmap/*tracked* (pmap/create-tracked)]
                 (let [file (feat.fdata/enable-pointer-map file)]
                   (feat.fdata/persist-pointers! cfg id)
                   file))
               file)

        file (-> file
                 (update :features db/encode-pgarray conn "text")
                 (update :data blob/encode))]

    ;; If file was already offloaded, we touch the underlying storage
    ;; object for properly trigger storage-gc-touched task
    (when (feat.fdata/offloaded? file)
      (some->> (:data-ref-id file) (sto/touch-object! storage)))

    (db/update! conn :file
                {:has-media-trimmed true
                 :features (:features file)
                 :version (:version file)
                 :data (:data file)
                 :data-backend nil
                 :data-ref-id nil}
                {:id id}
                {::db/return-keys true})))

(defn- process-file!
  [cfg]
  (if-let [file (get-file cfg)]
    (let [file (decode-file cfg file)
          file (clean-media! cfg file)
          file (persist-file! cfg file)]
      (clean-data-fragments! cfg file)
      true)

    (do
      (l/dbg :hint "skip" :file-id (str (::file-id cfg)))
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool ::sto/storage]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [min-age (dt/duration (or (:min-age props)
                                   (cf/get-deletion-delay)))
          cfg     (-> cfg
                      (assoc ::db/rollback (:rollback? props))
                      (assoc ::file-id (:file-id props))
                      (assoc ::min-age (db/interval min-age)))]

      (try
        (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                          (let [cfg        (update cfg ::sto/storage sto/configure conn)
                                processed? (process-file! cfg)]
                            (when (and processed? (contains? cf/flags :tiered-file-data-storage))
                              (wrk/submit! (-> cfg
                                               (assoc ::wrk/task :offload-file-data)
                                               (assoc ::wrk/params props)
                                               (assoc ::wrk/priority 10)
                                               (assoc ::wrk/delay 1000))))
                            processed?)))

        (catch Throwable cause
          (l/err :hint "error on cleaning file"
                 :file-id (str (:file-id props))
                 :cause cause))))))
