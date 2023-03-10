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
   [app.common.logging :as l]
   [app.common.pages.migrations :as pmg]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc.commands.files :as files]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private retrieve-candidates)
(declare ^:private process-file)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age cf/deletion-delay))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool] :as cfg}]
  (fn [{:keys [file-id] :as params}]
    (db/with-atomic [conn pool]
      (let [min-age (or (:min-age params) (::min-age cfg))
            cfg     (assoc cfg ::min-age min-age ::conn conn ::file-id file-id)]
        (loop [total 0
               files (retrieve-candidates cfg)]
          (if-let [file (first files)]
            (do
              (process-file conn file)
              (recur (inc total)
                     (rest files)))
            (do
              (l/info :hint "task finished" :min-age (dt/format-duration min-age) :processed total)

              ;; Allow optional rollback passed by params
              (when (:rollback? params)
                (db/rollback! conn))

              {:processed total})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  sql:retrieve-candidates-chunk
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

(defn- retrieve-candidates
  [{:keys [::conn ::min-age ::file-id]}]
  (if (uuid? file-id)
    (do
      (l/warn :hint "explicit file id passed on params" :file-id file-id)
      (->> (db/query conn :file {:id file-id})
           (map #(update % :features db/decode-pgarray #{}))))
    (let [interval  (db/interval min-age)
          get-chunk (fn [cursor]
                      (let [rows (db/exec! conn [sql:retrieve-candidates-chunk interval cursor])]
                        [(some->> rows peek :modified-at)
                         (map #(update % :features db/decode-pgarray #{}) rows)]))]

      (d/iteration get-chunk
                   :vf second
                   :kf first
                   :initk (dt/now)))))

(defn collect-used-media
  "Analyzes the file data and collects all references to external
  assets. Returns a set of ids."
  [data]
  (let [xform (comp
               (map :objects)
               (mapcat vals)
               (keep (fn [{:keys [type] :as obj}]
                       (case type
                         :path (get-in obj [:fill-image :id])
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

(defn- clean-file-frame-thumbnails!
  [conn file-id data]
  (let [stored (->> (db/query conn :file-object-thumbnail
                              {:file-id file-id}
                              {:columns [:object-id]})
                    (into #{} (map :object-id)))

        get-objects-ids
        (fn [{:keys [id objects]}]
          (->> (ctt/get-frames objects)
               (map #(str id (:id %)))))

        using (into #{}
                    (mapcat get-objects-ids)
                    (vals (:pages-index data)))

        unused (set/difference stored using)]

    (when (seq unused)
      (let [sql (str "delete from file_object_thumbnail "
                     " where file_id=? and object_id=ANY(?)")
            res (db/exec-one! conn [sql file-id (db/create-array conn "text" unused)])]
        (l/debug :hint "delete file object thumbnails" :file-id file-id :total (:next.jdbc/update-count res))))))

(defn- clean-file-thumbnails!
  [conn file-id revn]
  (let [sql (str "delete from file_thumbnail "
                 " where file_id=? and revn < ?")
        res (db/exec-one! conn [sql file-id revn])]
    (when-not (zero? (:next.jdbc/update-count res))
      (l/debug :hint "delete file thumbnails" :file-id file-id :total (:next.jdbc/update-count res)))))

(def ^:private
  sql:retrieve-client-files
  "select f.data, f.modified_at
     from file as f
     left join file_library_rel as fl on (fl.file_id = f.id)
    where fl.library_file_id = ?
      and f.modified_at < ?
      and f.deleted_at is null
    order by f.modified_at desc
    limit 1")

(defn- retrieve-client-files
  "search al files that use the given library.
   Returns a sequence of file-data (only reads database rows one by one)."
  [conn library-id]
  (let [get-chunk (fn [cursor]
                    (let [rows (db/exec! conn [sql:retrieve-client-files library-id cursor])]
                      [(some-> rows peek :modified-at)
                       (map (comp blob/decode :data) rows)]))]

    (d/iteration get-chunk
                 :vf second
                 :kf first
                 :initk (dt/now))))

(defn- clean-deleted-components!
  "Performs the garbage collection of unreferenced deleted components."
  [conn library-id library-data]
  (let [find-used-components-file
        (fn [components file-data]
          ; Find which of the components are used in the file.
          (into #{}
                (filter #(ctf/used-in? file-data library-id % :component))
                components))

        find-unused-components
        (fn [components files-data]
          ; Find what components are NOT used in any of the files.
          (loop [files-data      files-data
                 components      components]
            (let [file-data (first files-data)]
              (if (or (nil? file-data) (empty? components))
                components
                (let [used-components-file (find-used-components-file components file-data)]
                  (recur (rest files-data)
                         (into #{} (remove used-components-file) components)))))))

        deleted-components     (set (ctkl/deleted-components-seq library-data))
        unused-components      (find-unused-components deleted-components
                                                       (cons library-data
                                                             (retrieve-client-files conn library-id)))
        total                  (count unused-components)]

    (when-not (zero? total)
      (l/debug :hint "clean deleted components" :total total)
      (let [new-data (reduce #(ctkl/delete-component %1 (:id %2))
                             library-data
                             unused-components)]
        (db/update! conn :file
                    {:data (blob/encode new-data)}
                    {:id library-id})))))

(def ^:private sql:get-unused-fragments
  "SELECT id FROM file_data_fragment
    WHERE file_id = ? AND id != ALL(?::uuid[])")

(defn- clean-data-fragments!
  [conn file-id data]
  (let [used (->> (concat (vals data)
                          (vals (:pages-index data)))
                  (into #{} (comp (filter pmap/pointer-map?)
                                  (map pmap/get-id)))
                  (db/create-array conn "uuid"))
        rows (db/exec! conn [sql:get-unused-fragments file-id used])]
    (doseq [fragment-id (map :id rows)]
      (l/trace :hint "remove unused file data fragment" :id (str fragment-id))
      (db/delete! conn :file-data-fragment {:id fragment-id :file-id file-id}))))

(defn- process-file
  [conn {:keys [id data revn modified-at features] :as file}]
  (l/debug :hint "processing file" :id id :modified-at modified-at)

  (binding [pmap/*load-fn* (partial files/load-pointer conn id)]
    (let [data (-> (blob/decode data)
                   (assoc :id id)
                   (pmg/migrate-data))]

      (clean-file-media! conn id data)
      (clean-file-frame-thumbnails! conn id data)
      (clean-file-thumbnails! conn id revn)
      (clean-deleted-components! conn id data)

      (when (contains? features "storage/pointer-map")
        (clean-data-fragments! conn id data))

      ;; Mark file as trimmed
      (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id id})
      nil)))
