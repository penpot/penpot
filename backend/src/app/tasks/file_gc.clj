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
   [app.common.files.migrations :as pmg]
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

(declare ^:private get-candidates)
(declare ^:private clean-file!)

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
                                          (clean-file! cfg file)
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  sql:get-candidates
  "SELECT f.id,
          f.data,
          f.revn,
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
      (->> (db/query conn :file {:id file-id})
           (map #(update % :features db/decode-pgarray #{}))))

    (let [min-age (db/interval min-age)]
      (->> (db/cursor conn [sql:get-candidates min-age] {:chunk-size 1})
           (map #(update % :features db/decode-pgarray #{}))))))

(defn collect-used-media
  "Given a fdata (file data), returns all media references."
  [data]
  (let [xform (comp
               (map :objects)
               (mapcat vals)
               (mapcat (fn [obj]
                         ;; NOTE: because of some bug, we ended with
                         ;; many shape types having the ability to
                         ;; have fill-image attribute (which initially
                         ;; designed for :path shapes).
                         (sequence
                          (keep :id)
                          (concat [(:fill-image obj)
                                   (:metadata obj)]
                                  (map :fill-image (:fills obj))
                                  (map :stroke-image (:strokes obj))
                                  (->> (:content obj)
                                       (tree-seq map? :children)
                                       (mapcat :fills)
                                       (map :fill-image)))))))
        pages (concat
               (vals (:pages-index data))
               (vals (:components data)))]
    (-> #{}
        (into xform pages)
        (into (keys (:media data))))))


(def ^:private sql:mark-file-media-object-deleted
  "UPDATE file_media_object
      SET deleted_at = now()
    WHERE file_id = ? AND id != ALL(?::uuid[])
   RETURNING id")

(defn- clean-file-media!
  "Performs the garbage collection of file media objects."
  [conn file-id data]
  (let [used   (collect-used-media data)
        ids    (db/create-array conn "uuid" used)
        unused (->> (db/exec! conn [sql:mark-file-media-object-deleted file-id ids])
                    (into #{} (map :id)))]

    (doseq [id unused]
      (l/trc :hint "mark deleted"
             :rel "file-media-object"
             :id (str id)
             :file-id (str file-id)))

    (count unused)))


(def ^:private sql:mark-file-object-thumbnails-deleted
  "UPDATE file_tagged_object_thumbnail
      SET deleted_at = now()
    WHERE file_id = ? AND object_id != ALL(?::text[])
   RETURNING object_id")

(defn- clean-file-object-thumbnails!
  [{:keys [::db/conn]} file-id data]
  (let [using  (->> (vals (:pages-index data))
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

    (count unused)))


(def ^:private sql:mark-file-thumbnails-deleted
  "UPDATE file_thumbnail
      SET deleted_at = now()
    WHERE file_id = ? AND revn < ?
   RETURNING revn")

(defn- clean-file-thumbnails!
  [{:keys [::db/conn]} file-id revn]
  (let [unused (->> (db/exec! conn [sql:mark-file-thumbnails-deleted file-id revn])
                    (into #{} (map :revn)))]

    (doseq [revn unused]
      (l/trc :hint "mark deleted"
             :rel "file-thumbnail"
             :revn revn
             :file-id (str file-id)))

    (count unused)))


(def ^:private sql:get-files-for-library
  "SELECT f.id, f.data, f.modified_at
     FROM file AS f
     LEFT JOIN file_library_rel AS fl ON (fl.file_id = f.id)
    WHERE fl.library_file_id = ?
      AND f.deleted_at IS null
    ORDER BY f.modified_at ASC")

(defn- clean-deleted-components!
  "Performs the garbage collection of unreferenced deleted components."
  [{:keys [::db/conn] :as cfg} file-id data]
  (letfn [(get-used-components [fdata components]
            ;; Find which of the components are used in the file.
            (into #{}
                  (filter #(ctf/used-in? fdata file-id % :component))
                  components))

          (get-unused-components [components files-data]
            ;; Find and return a set of unused components (on all files).
            (reduce (fn [components fdata]
                      (if (seq components)
                        (->> (get-used-components fdata components)
                             (set/difference components))
                        (reduced components)))

                    components
                    files-data))]

    (let [deleted (into #{} (ctkl/deleted-components-seq data))
          unused  (->> (db/cursor conn [sql:get-files-for-library file-id] {:chunk-size 1})
                       (map (fn [{:keys [id data] :as file}]
                              (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)]
                                (-> (blob/decode data)
                                    (feat.fdata/process-pointers deref)))))
                       (cons data)
                       (get-unused-components deleted)
                       (mapv :id))]

      (doseq [id unused]
        (l/trc :hint "delete component" :component-id (str id) :file-id (str file-id)))


      (when-let [data (some->> (seq unused)
                               (reduce ctkl/delete-component data)
                               (blob/encode))]
        (db/update! conn :file
                    {:data data}
                    {:id file-id}
                    {::db/return-keys? false}))

      (count unused))))


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
  [conn file-id data]
  (let [used   (->> (db/cursor conn [sql:get-changes file-id])
                    (into (feat.fdata/get-used-pointer-ids data)
                          (comp (map :data)
                                (map blob/decode)
                                (mapcat feat.fdata/get-used-pointer-ids))))

        unused (let [ids (db/create-array conn "uuid" used)]
                 (->> (db/exec! conn [sql:mark-deleted-data-fragments file-id ids])
                      (into #{} (map :id))))]

    (doseq [id unused]
      (l/trc :hint "mark deleted"
             :rel "file-data-fragment"
             :id (str id)
             :file-id (str file-id)))

    (count unused)))


(defn- clean-file!
  [{:keys [::db/conn] :as cfg} {:keys [id data revn modified-at] :as file}]

  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)
            pmap/*tracked* (pmap/create-tracked)]
    (let [data (-> (blob/decode data)
                   (assoc :id id)
                   (pmg/migrate-data))

          nfm  (clean-file-media! conn id data)
          nfot (clean-file-object-thumbnails! cfg id data)
          nft  (clean-file-thumbnails! cfg id revn)
          nc   (clean-deleted-components! cfg id data)
          ndf  (clean-data-fragments! conn id data)]

      (l/dbg :hint "file clened"
             :file-id (str id)
             :modified-at (dt/format-instant modified-at)
             :media-objects nfm
             :thumbnails nft
             :object-thumbnails nfot
             :components nc
             :data-fragments ndf)

      ;; Mark file as trimmed
      (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id id}
                  {::db/return-keys? false})

      (feat.fdata/persist-pointers! cfg id))))
