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
   [app.common.data :as d]
   [app.common.files.migrations :as pmg]
   [app.common.logging :as l]
   [app.common.thumbnails :as thc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.commands.files :as files]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private get-candidates)
(declare ^:private process-file)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool ::sto/storage]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age cf/deletion-delay))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool] :as cfg}]
  (fn [{:keys [file-id] :as params}]

    (db/with-atomic [conn pool]
      (let [min-age (dt/duration (or (:min-age params) (::min-age cfg)))
            cfg     (-> cfg
                        (update ::sto/storage media/configure-assets-storage conn)
                        (assoc ::db/conn conn)
                        (assoc ::file-id file-id)
                        (assoc ::min-age min-age))

            total   (reduce (fn [total file]
                              (process-file cfg file)
                              (inc total))
                            0
                            (get-candidates cfg))]

        (l/info :hint "task finished" :min-age (dt/format-duration min-age) :processed total)

        ;; Allow optional rollback passed by params
        (when (:rollback? params)
          (db/rollback! conn))

        {:processed total}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  sql:get-candidates-chunk
  "select f.id,
          f.data,
          f.revn,
          f.features,
          f.modified_at
     from file as f
    where f.has_media_trimmed is false
      and f.modified_at < now() - ?::interval
      and f.modified_at < ?
    order by f.modified_at desc
    limit 1
    for update skip locked")

(defn- get-candidates
  [{:keys [::db/conn ::min-age ::file-id]}]
  (if (uuid? file-id)
    (do
      (l/warn :hint "explicit file id passed on params" :file-id file-id)
      (->> (db/query conn :file {:id file-id})
           (map #(update % :features db/decode-pgarray #{}))))
    (let [interval  (db/interval min-age)
          get-chunk (fn [cursor]
                      (let [rows (db/exec! conn [sql:get-candidates-chunk interval cursor])]
                        [(some->> rows peek :modified-at)
                         (map #(update % :features db/decode-pgarray #{}) rows)]))]

      (d/iteration get-chunk
                   :vf second
                   :kf first
                   :initk (dt/now)))))

(defn collect-used-media
  "Given a fdata (file data), returns all media references."
  [data]
  (let [xform (comp
               (map :objects)
               (mapcat vals)
               (keep (fn [{:keys [type] :as obj}]
                       (case type
                         :path  (get-in obj [:fill-image :id])
                         :bool  (get-in obj [:fill-image :id])
                         ;; NOTE: because of some bug, we ended with
                         ;; many shape types having the ability to
                         ;; have fill-image attribute (which initially
                         ;; designed for :path shapes).
                         :group (get-in obj [:fill-image :id])
                         :image (get-in obj [:metadata :id])

                         nil))))
        pages (concat
               (vals (:pages-index data))
               (vals (:components data)))]
    (-> #{}
        (into xform pages)
        (into (keys (:media data))))))

(defn- clean-file-media!
  "Performs the garbage collection of file media objects."
  [conn file-id data]
  (let [used   (collect-used-media data)
        unused (->> (db/query conn :file-media-object {:file-id file-id})
                    (remove #(contains? used (:id %))))]

    (doseq [mobj unused]
      (l/debug :hint "delete file media object"
               :id (:id mobj)
               :media-id (:media-id mobj)
               :thumbnail-id (:thumbnail-id mobj))

      ;; NOTE: deleting the file-media-object in the database
      ;; automatically marks as touched the referenced storage
      ;; objects. The touch mechanism is needed because many files can
      ;; point to the same storage objects and we can't just delete
      ;; them.
      (db/delete! conn :file-media-object {:id (:id mobj)}))))

(defn- clean-file-object-thumbnails!
  [{:keys [::db/conn ::sto/storage]} file-id data]
  (let [stored (->> (db/query conn :file_object_thumbnail
                              {:file-id file-id}
                              {:columns [:object-id]})
                    (into #{} (map :object-id)))

        using  (into #{}
                     (mapcat
                      (fn [{:keys [id objects]}]
                        (->> (ctt/get-frames objects)
                             (mapcat
                              #(vector
                                (thc/fmt-object-id file-id id (:id %) "frame")
                                (thc/fmt-object-id file-id id (:id %) "component"))))))
                     (vals (:pages-index data)))

        unused (set/difference stored using)]

    (when (seq unused)
      (let [sql (str "delete from file_object_thumbnail "
                     " where file_id=? and object_id=ANY(?)"
                     " returning media_id")
            res (db/exec! conn [sql file-id (db/create-array conn "text" unused)])]

        (doseq [media-id (into #{} (keep :media-id) res)]
          ;; Mark as deleted the storage object related with the
          ;; photo-id field.
          (l/trace :hint "mark storage object as deleted" :id media-id)
          (sto/del-object! storage media-id))

        (l/debug :hint "delete file object thumbnails"
                 :file-id file-id
                 :total (count res))))))

(defn- clean-file-thumbnails!
  [{:keys [::db/conn ::sto/storage]} file-id revn]
  (let [sql (str "delete from file_thumbnail "
                 " where file_id=? and revn < ? "
                 " returning media_id")
        res (db/exec! conn [sql file-id revn])]

    (when (seq res)
      (doseq [media-id (into #{} (keep :media-id) res)]
        ;; Mark as deleted the storage object related with the
        ;; media-id field.
        (l/trace :hint "mark storage object as deleted" :id media-id)
        (sto/del-object! storage media-id))

      (l/debug :hint "delete file thumbnails"
               :file-id file-id
               :total (count res)))))

(def ^:private
  sql:get-files-for-library
  "select f.data, f.modified_at
     from file as f
     left join file_library_rel as fl on (fl.file_id = f.id)
    where fl.library_file_id = ?
      and f.modified_at < ?
      and f.deleted_at is null
    order by f.modified_at desc
    limit 1")

(defn- clean-deleted-components!
  "Performs the garbage collection of unreferenced deleted components."
  [conn file-id data]
  (letfn [(get-files-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-files-for-library file-id cursor])]
              [(some-> rows peek :modified-at)
               (map (comp blob/decode :data) rows)]))

          (get-used-components [fdata components]
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
          unused  (->> (d/iteration get-files-chunk :vf second :kf first :initk (dt/now))
                       (cons data)
                       (get-unused-components deleted)
                       (mapv :id))]

      (when (seq unused)
        (l/debug :hint "clean deleted components" :total (count unused))

        (let [data (reduce ctkl/delete-component data unused)]
          (db/update! conn :file
                      {:data (blob/encode data)}
                      {:id file-id}))))))

(defn- clean-data-fragments!
  [conn file-id data]
  (letfn [(get-pointers-chunk [cursor]
            (let [sql  (str "select id, data, created_at "
                            "  from file_change "
                            " where file_id = ? "
                            "   and data is not null "
                            "   and created_at < ? "
                            " order by created_at desc "
                            " limit 1;")
                  rows (db/exec! conn [sql file-id cursor])]
              [(some-> rows peek :created-at)
               (mapcat (comp files/get-all-pointer-ids blob/decode :data) rows)]))]

    (let [used (into (files/get-all-pointer-ids data)
                     (d/iteration get-pointers-chunk
                                  :vf second
                                  :kf first
                                  :initk (dt/now)))

          sql  (str "select id from file_data_fragment "
                    " where file_id = ? AND id != ALL(?::uuid[])")
          used (db/create-array conn "uuid" used)
          rows (db/exec! conn [sql file-id used])]

      (doseq [fragment-id (map :id rows)]
        (l/trace :hint "remove unused file data fragment" :id (str fragment-id))
        (db/delete! conn :file-data-fragment {:id fragment-id :file-id file-id})))))

(defn- process-file
  [{:keys [::db/conn] :as cfg} {:keys [id data revn modified-at features] :as file}]
  (l/debug :hint "processing file" :id id :modified-at modified-at)

  (binding [pmap/*load-fn* (partial files/load-pointer conn id)
            pmap/*tracked* (atom {})]
    (let [data (-> (blob/decode data)
                   (assoc :id id)
                   (pmg/migrate-data))]

      (clean-file-media! conn id data)
      (clean-file-object-thumbnails! cfg id data)
      (clean-file-thumbnails! cfg id revn)
      (clean-deleted-components! conn id data)

      (when (contains? features "fdata/pointer-map")
        (clean-data-fragments! conn id data))

      ;; Mark file as trimmed
      (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id id})

      (files/persist-pointers! conn id))))
