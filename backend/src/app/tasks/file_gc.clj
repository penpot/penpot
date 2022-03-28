;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.file-gc
  "A maintenance task that is responsible of: purge unused file media,
  clean unused frame thumbnails and remove old file thumbnails.  The
  file is eligible to be garbage collected after some period of
  inactivity (the default threshold is 72h)."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.pages.helpers :as cph]
   [app.common.pages.migrations :as pmg]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private retrieve-candidates)
(declare ^:private process-file)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::max-age]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [_]
    (db/with-atomic [conn pool]
      (let [cfg (assoc cfg :conn conn)]
        (loop [total 0
               files (retrieve-candidates cfg)]
          (if-let [file (first files)]
            (do
              (process-file cfg file)
              (recur (inc total)
                     (rest files)))
            (do
              (l/debug :msg "finished processing files" :processed total)
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
  [{:keys [conn max-age] :as cfg}]
  (let [interval (db/interval max-age)

        get-chunk
        (fn [cursor]
          (let [rows (db/exec! conn [sql:retrieve-candidates-chunk interval cursor])]
            [(some->> rows peek :modified-at) (seq rows)]))]

    (sequence cat (d/iteration get-chunk
                               :vf second
                               :kf first
                               :initk (dt/now)))))

(defn- collect-used-media
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

(defn- collect-frames
  [data]
  (let [xform (comp
               (map :objects)
               (mapcat vals)
               (filter cph/frame-shape?)
               (keep :id))
        pages (concat
               (vals (:pages-index data))
               (vals (:components data)))]
    (into #{} xform pages)))

(defn- clean-file-frame-thumbnails!
  [conn file-id data]
  (let [sql (str "delete from file_frame_thumbnail "
                 " where file_id=? and not (frame_id=ANY(?))")
        ids (->> (collect-frames data)
                 (db/create-array conn "uuid"))
        res (db/exec-one! conn [sql file-id ids])]
    (l/debug :hint "delete frame thumbnails" :total (:next.jdbc/update-count res))))

(defn- clean-file-thumbnails!
  [conn file-id revn]
  (let [sql (str "delete from file_thumbnail "
                 " where file_id=? and revn < ?")
        res (db/exec-one! conn [sql file-id revn])]
    (l/debug :hint "delete file thumbnails" :total (:next.jdbc/update-count res))))

(defn- process-file
  [{:keys [conn] :as cfg} {:keys [id data revn modified-at] :as file}]
  (l/debug :hint "processing file" :id id :modified-at modified-at)

  (let [data (-> (blob/decode data)
                 (assoc :id id)
                 (pmg/migrate-data))]

    (clean-file-media! conn id data)
    (clean-file-frame-thumbnails! conn id data)
    (clean-file-thumbnails! conn id revn)

    ;; Mark file as trimmed
    (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id id})
    nil))
