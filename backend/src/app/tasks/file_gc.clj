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
   [app.media :as media]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private clean-file!)

(defn- decode-file
  [cfg {:keys [id] :as file}]
  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)]
    (-> file
        (update :features db/decode-pgarray #{})
        (update :data blob/decode)
        (update :data feat.fdata/process-pointers deref)
        (update :data feat.fdata/process-objects (partial into {}))
        (update :data assoc :id id)
        (fmg/migrate-file))))

(defn- update-file!
  [{:keys [::db/conn] :as cfg} {:keys [id] :as file}]
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

    (db/update! conn :file
                {:has-media-trimmed true
                 :features (:features file)
                 :version (:version file)
                 :data (:data file)}
                {:id id})))

(defn- process-file!
  [cfg file]
  (try
    (let [file (decode-file cfg file)
          file (clean-file! cfg file)]
      (cfv/validate-file-schema! file)
      (update-file! cfg file))
    (catch Throwable cause
      (l/err :hint "error on cleaning file (skiping)"
             :file-id (str (:id file))
             :cause cause))))

(def ^:private
  sql:get-candidates
  "SELECT f.id,
          f.data,
          f.revn,
          f.version,
          f.features,
          f.modified_at
     FROM file AS f
    WHERE f.has_media_trimmed IS false
      AND f.modified_at < now() - ?::interval
    ORDER BY f.modified_at DESC
      FOR UPDATE
     SKIP LOCKED")

(defn- get-candidates
  [{:keys [::db/conn ::min-age ::file-id]}]
  (if (uuid? file-id)
    (do
      (l/warn :hint "explicit file id passed on params" :file-id (str file-id))
      (db/query conn :file {:id file-id}))

    (let [min-age (db/interval min-age)]
      (db/cursor conn [sql:get-candidates min-age] {:chunk-size 1}))))

(def ^:private sql:mark-file-media-object-deleted
  "UPDATE file_media_object
      SET deleted_at = now()
    WHERE file_id = ? AND id != ALL(?::uuid[])
   RETURNING id")

(defn- clean-file-media!
  "Performs the garbage collection of file media objects."
  [{:keys [::db/conn]} {:keys [id data] :as file}]
  (let [used   (bfc/collect-used-media data)
        ids    (db/create-array conn "uuid" used)
        unused (->> (db/exec! conn [sql:mark-file-media-object-deleted id ids])
                    (into #{} (map :id)))]

    (doseq [id unused]
      (l/trc :hint "mark deleted"
             :rel "file-media-object"
             :id (str id)
             :file-id (str id)))

    [(count unused) file]))

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

    (doseq [object-id unused]
      (l/trc :hint "mark deleted"
             :rel "file-tagged-object-thumbnail"
             :object-id object-id
             :file-id (str file-id)))

    [(count unused) file]))

(def ^:private sql:mark-file-thumbnails-deleted
  "UPDATE file_thumbnail
      SET deleted_at = now()
    WHERE file_id = ? AND revn < ?
   RETURNING revn")

(defn- clean-file-thumbnails!
  [{:keys [::db/conn]} {:keys [id revn] :as file}]
  (let [unused (->> (db/exec! conn [sql:mark-file-thumbnails-deleted id revn])
                    (into #{} (map :revn)))]

    (doseq [revn unused]
      (l/trc :hint "mark deleted"
             :rel "file-thumbnail"
             :revn revn
             :file-id (str id)))

    [(count unused) file]))


(def ^:private sql:get-files-for-library
  "SELECT f.id, f.data, f.modified_at, f.features, f.version
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

    [(count unused) file]))

(def ^:private sql:get-changes
  "SELECT id, data FROM file_change
    WHERE file_id = ? AND data IS NOT NULL
    ORDER BY created_at ASC")

(def ^:private sql:mark-deleted-data-fragments
  "UPDATE file_data_fragment
      SET deleted_at = now()
    WHERE file_id = ?
      AND id != ALL(?::uuid[])
   RETURNING id")

(defn- clean-data-fragments!
  [{:keys [::db/conn]} {:keys [id data] :as file}]
  (let [used   (->> (db/cursor conn [sql:get-changes id])
                    (into (feat.fdata/get-used-pointer-ids data)
                          (comp (map :data)
                                (map blob/decode)
                                (mapcat feat.fdata/get-used-pointer-ids))))

        unused (let [ids (db/create-array conn "uuid" used)]
                 (->> (db/exec! conn [sql:mark-deleted-data-fragments id ids])
                      (into #{} (map :id))))]

    (doseq [id unused]
      (l/trc :hint "mark deleted"
             :rel "file-data-fragment"
             :id (str id)
             :file-id (str id)))

    [(count unused) file]))

(defn- clean-file!
  [cfg {:keys [id] :as file}]
  (let [[n1 file] (clean-file-media! cfg file)
        [n2 file] (clean-file-thumbnails! cfg file)
        [n3 file] (clean-file-object-thumbnails! cfg file)
        [n4 file] (clean-deleted-components! cfg file)
        [n5 file] (clean-data-fragments! cfg file)]

    (l/dbg :hint "file clened"
           :file-id (str id)
           :modified-at (dt/format-instant (:modified-at file))
           :media-objects n1
           :thumbnails n2
           :object-thumbnails n3
           :components n4
           :data-fragments n5)

    file))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool ::sto/storage]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age cf/deletion-delay))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [file-id] :as params}]
    (db/tx-run! cfg
                (fn [{:keys [::db/conn] :as cfg}]
                  (let [min-age (dt/duration (or (:min-age params) (::min-age cfg)))
                        cfg     (-> cfg
                                    (update ::sto/storage media/configure-assets-storage conn)
                                    (assoc ::file-id file-id)
                                    (assoc ::min-age min-age))

                        total   (reduce (fn [total file]
                                          (process-file! cfg file)
                                          (inc total))
                                        0
                                        (get-candidates cfg))]

                    (l/inf :hint "task finished"
                           :min-age (dt/format-duration min-age)
                           :processed total)

                    ;; Allow optional rollback passed by params
                    (when (:rollback? params)
                      (db/rollback! conn))

                    {:processed total})))))
