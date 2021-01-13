;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.file-media-gc
  "A maintenance task that is responsible to purge the unused media
  objects from files. A file is ellegible to be garbage collected
  after some period of inactivity (the default threshold is 72h)."
  (:require
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.tasks :as tasks]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(declare handler)
(declare retrieve-candidates)
(declare process-file)

(s/def ::storage some?)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::storage]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (let [handler #(handler cfg %)]
    (->> {:registry (:registry metrics)
          :type :summary
          :name "task_file_media_gc_timing"
          :help "file media garbage collection task timing"}
         (mtx/instrument handler))))

(defn- handler
  [{:keys [pool] :as cfg} _]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)]
      (loop []
        (let [files (retrieve-candidates cfg)]
          (when files
            (run! (partial process-file cfg) files)
            (recur)))))))

(defn- decode-row
  [{:keys [data] :as row}]
  (cond-> row
    (bytes? data) (assoc :data (blob/decode data))))

(def ^:private
  sql:retrieve-candidates-chunk
  "select f.id,
          f.data,
          extract(epoch from (now() - f.modified_at))::bigint as age
     from file as f
    where f.has_media_trimmed is false
      and f.modified_at < now() - ?::interval
    order by f.modified_at asc
    limit 10
    for update skip locked")

(defn- retrieve-candidates
  [{:keys [conn max-age] :as cfg}]
  (let [interval (db/interval max-age)]
    (->> (db/exec! conn [sql:retrieve-candidates-chunk interval])
         (map (fn [{:keys [age] :as row}]
                (assoc row :age (dt/duration {:seconds age}))))
         (seq))))

(def ^:private
  collect-media-xf
  (comp
   (map :objects)
   (mapcat vals)
   (filter #(= :image (:type %)))
   (map :metadata)
   (map :id)))

(defn- collect-used-media
  [data]
  (-> #{}
      (into collect-media-xf (vals (:pages-index data)))
      (into collect-media-xf (vals (:components data)))
      (into (keys (:media data)))))

(defn- process-file
  [{:keys [conn storage] :as cfg} {:keys [id data age] :as file}]
  (let [data    (-> (blob/decode data)
                    (assoc :id id)
                    (pmg/migrate-data))

        used    (collect-used-media data)
        unused  (->> (db/query conn :file-media-object {:file-id id})
                     (remove #(contains? used (:id %))))]

    (log/infof "processing file: id='%s' age='%s' to-delete=%s" id age (count unused))

    ;; Mark file as trimmed
    (db/update! conn :file
                {:has-media-trimmed true}
                {:id id})

    (doseq [mobj unused]
      (log/debugf "deleting media object: id='%s' media-id='%s' thumb-id='%s'"
                  (:id mobj) (:media-id mobj) (:thumbnail-id mobj))
      ;; NOTE: deleting the file-media-object in the database
      ;; automatically marks to be deleted the associated storage
      ;; objects with the specialized trigger attached
      ;; to :file-media-object table.
      (db/delete! conn :file-media-object {:id (:id mobj)}))

    nil))
