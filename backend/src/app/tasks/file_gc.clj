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
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(declare ^:private retrieve-candidates)
(declare ^:private process-file)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::min-age]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (merge {:min-age cf/deletion-delay}
         (d/without-nils cfg)))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [params]
    (db/with-atomic [conn pool]
      (let [min-age (or (:min-age params) (:min-age cfg))
            id      (:id params)
            cfg     (assoc cfg :min-age min-age :conn conn :id id)]
        (loop [total 0
               files (retrieve-candidates cfg)]
          (if-let [file (first files)]
            (do
              (process-file cfg file)
              (recur (inc total)
                     (rest files)))
            (do
              (l/info :hint "task finished" :min-age (dt/format-duration min-age) :total total)

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
          f.modified_at
     from file as f
    where f.has_media_trimmed is false
      and f.modified_at < now() - ?::interval
      and f.modified_at < ?
    order by f.modified_at desc
    limit 1
    for update skip locked")

(defn- retrieve-candidates
  [{:keys [conn min-age id] :as cfg}]
  (if id
    (do
      (l/warn :hint "explicit file id passed on params" :id id)
      (db/query conn :file {:id id}))
    (let [interval  (db/interval min-age)
          get-chunk (fn [cursor]
                      (let [rows (db/exec! conn [sql:retrieve-candidates-chunk interval cursor])]
                        [(some->> rows peek :modified-at) (seq rows)]))]
      (d/iteration get-chunk
                   :vf second
                   :kf first
                   :initk (dt/now)))))

(defn collect-used-media
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
      (let [sql (str/concat
                 "delete from file_object_thumbnail "
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
                       (map #(->> % :data blob/decode) rows)]))]

    (d/iteration get-chunk
                 :vf second
                 :kf first
                 :initk (dt/now))))

(defn- clean-deleted-components!
  "Performs the garbage collection of unreferenced deleted components."
  [conn library-id library-data]
  (let [find-used-components-file
        (fn [components file-data]
          ; Find what of the components are used in the file.
          (d/filterm #(ctf/used-in? file-data library-id (second %) :component)
                     components))

        find-used-components
        (fn [components files-data]
          ; Find what components are used in any of the files.
          (loop [files-data      files-data
                 components      components
                 used-components {}]
            (let [file-data (first files-data)]
              (if (or (nil? file-data) (empty? components))
                used-components
                (let [used-components-file (find-used-components-file components file-data)]
                  (recur (rest files-data)
                         (d/filterm #(not (contains? used-components-file (:id %))) components)
                         (into used-components used-components-file)))))))

        deleted-components (:deleted-components library-data)
        saved-components   (find-used-components deleted-components
                                                 (cons library-data
                                                       (retrieve-client-files conn library-id)))

        total (- (count deleted-components)
                 (count saved-components))]

    (when-not (zero? total)
      (l/debug :hint "clean deleted components" :total total)
      (let [new-data (-> library-data
                         (assoc :deleted-components saved-components)
                         (blob/encode))]
        (db/update! conn :file
                    {:data new-data}
                    {:id library-id})))))

(defn- process-file
  [{:keys [conn] :as cfg} {:keys [id data revn modified-at] :as file}]
  (l/debug :hint "processing file" :id id :modified-at modified-at)

  (let [data (-> (blob/decode data)
                 (assoc :id id)
                 (pmg/migrate-data))]

    (clean-file-media! conn id data)
    (clean-file-frame-thumbnails! conn id data)
    (clean-file-thumbnails! conn id revn)
    (clean-deleted-components! conn id data)

    ;; Mark file as trimmed
    (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id id})
    nil))
