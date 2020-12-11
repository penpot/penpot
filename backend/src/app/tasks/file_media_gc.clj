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
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.tools.logging :as log]))

(defn decode-row
  [{:keys [data] :as row}]
  (cond-> row
    (bytes? data) (assoc :data (blob/decode data))))

(def sql:retrieve-candidates-chunk
  "select f.id,
          f.data,
          extract(epoch from (now() - f.modified_at))::bigint as age
     from file as f
    where f.has_media_trimmed is false
      and f.modified_at < now() - ?::interval
    order by f.modified_at asc
    limit 10
    for update skip locked")

(defn retrieve-candidates
  "Retrieves a list of files that are candidates to be garbage
  collected."
  [conn]
  (let [threshold (:file-trimming-threshold cfg/config)
        interval  (db/interval threshold)]
    (->> (db/exec! conn [sql:retrieve-candidates-chunk interval])
         (map (fn [{:keys [age] :as row}]
                (assoc row :age (dt/duration {:seconds age})))))))

(def collect-media-xf
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
  [conn {:keys [id data age] :as file}]
  (let [data    (-> (blob/decode data)
                    (assoc :id id)
                    (pmg/migrate-data))

        used    (collect-used-media data)
        unused  (->> (db/query conn :media-object {:file-id id})
                     (remove #(contains? used (:id %))))]

    (log/infof "processing file: id='%s' age='%s' to-delete=%s" id age (count unused))

    ;; Mark file as trimmed
    (db/update! conn :file
                {:has-media-trimmed true}
                {:id id})

    (doseq [mobj unused]
      (log/debugf "schduling object deletion: id='%s' path='%s' delay='%s'"
                  (:id mobj) (:path mobj) cfg/default-deletion-delay)
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :media-object}})

      ;; Mark object as deleted
      (db/update! conn :media-object
                  {:deleted-at (dt/now)}
                  {:id id}))

    nil))

(defn handler
  [_task]
  (log/debug "running 'file-media-gc' task.")
  (db/with-atomic [conn db/pool]
    (loop []
      (let [files (retrieve-candidates conn)]
        (when (seq files)
          (run! (partial process-file conn) files)
          (recur))))))
