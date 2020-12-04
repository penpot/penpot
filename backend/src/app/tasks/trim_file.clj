;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.trim-file
  (:require
   [app.common.pages.migrations :as pmg]
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Trim File
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is the task responsible of removing unnecesary media-objects
;; associated with file but not used by any page.

(defn decode-row
  [{:keys [data] :as row}]
  (cond-> row
    (bytes? data) (assoc :data (blob/decode data))))

(def sql:retrieve-files-to-trim
  "select f.id, f.data
     from file as f
    where f.has_media_trimmed is false
      and f.modified_at < now() - ?::interval
    order by f.modified_at asc
    limit 10")

(defn retrieve-candidates
  "Retrieves a list of ids of files that are candidates to be trimed. A
  file is considered candidate when some time passes whith no
  modification."
  [conn]
  (let [threshold (:file-trimming-threshold cfg/config)
        interval  (db/interval threshold)]
    (db/exec! conn [sql:retrieve-files-to-trim interval])))

(def collect-media-xf
  (comp
   (map :objects)
   (mapcat vals)
   (filter #(= :image (:type %)))
   (map :metadata)
   (map :id)))

(defn collect-used-media
  [data]
  (-> #{}
      (into collect-media-xf (vals (:pages-index data)))
      (into collect-media-xf (vals (:components data)))
      (into (keys (:media data)))))

(defn process-file
  [{:keys [id data] :as file}]
  (log/debugf "Processing file: '%s'." id)
  (db/with-atomic [conn db/pool]
    (let [mobjs  (map :id (db/query conn :media-object {:file-id id}))
          data   (-> (blob/decode data)
                     (assoc :id id)
                     (pmg/migrate-data))

          used   (collect-used-media data)
          unused (into #{} (remove #(contains? used %)) mobjs)]

      (log/debugf "Collected media ids: '%s'." (pr-str used))
      (log/debugf "Unused media ids: '%s'." (pr-str unused))

      (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id id})

      (doseq [id unused]
        ;; TODO: add task batching
        (tasks/submit! conn {:name "delete-object"
                             ;; :delay cfg/default-deletion-delay
                             :delay 10000
                             :props {:id id :type :media-object}})

        (db/update! conn :media-object
                    {:deleted-at (dt/now)}
                    {:id id}))
      nil)))

(defn handler
  [_task]
  (log/debug "Running 'trim-file' task.")
  (loop []
    (let [files (retrieve-candidates db/pool)]
      (when (seq files)
        (run! process-file files)
        (recur)))))
