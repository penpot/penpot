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
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.tasks :as tasks]
   [app.util.blob :as blob]
   [app.util.time :as dt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Trim File
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is the task responsible of removing unnecesary media-objects
;; associated with file but not used by any page.

(defn decode-row
  [{:keys [data metadata changes] :as row}]
  (cond-> row
    (bytes? data) (assoc :data (blob/decode data))))

(def sql:retrieve-files-to-trim
  "select id from file as f
    where f.has_media_trimmed is false
      and f.modified_at < now() - ?::interval
    order by f.modified_at asc
    limit 10")

(defn retrieve-candidates
  [conn]
  (let [interval (:file-trimming-max-age cfg/config)]
    (->> (db/exec! conn [sql:retrieve-files-to-trim interval])
         (map :id))))

(defn collect-used-media
  [pages]
  (let [xf (comp (filter #(= :image (:type %)))
                 (map :metadata)
                 (map :id))]
    (reduce conj #{} (->> pages
                          (map :data)
                          (map :objects)
                          (mapcat vals)
                          (filter #(= :image (:type %)))
                          (map :metadata)
                          (map :id)))))

(defn process-file
  [file-id]
  (log/debugf "Processing file: '%s'." file-id)
  (db/with-atomic [conn db/pool]
    (let [mobjs  (db/query conn :media-object {:file-id file-id})
          pages  (->> (db/query conn :page {:file-id file-id})
                      (map decode-row))
          used   (collect-used-media pages)
          unused (into #{} (comp (map :id)
                                 (remove #(contains? used %))) mobjs)]
      (log/debugf "Collected media ids: '%s'." (pr-str used))
      (log/debugf "Unused media ids: '%s'." (pr-str unused))

      (db/update! conn :file
                  {:has-media-trimmed true}
                  {:id file-id})

      (doseq [id unused]
        (tasks/submit! conn {:name "delete-object"
                             ;; :delay cfg/default-deletion-delay
                             :delay 10000
                             :props {:id id :type :media-object}})

        (db/update! conn :media-object
                    {:deleted-at (dt/now)}
                    {:id id}))
      nil)))

(defn handler
  [{:keys [props] :as task}]
  (log/debug "Running 'trim-file' task.")
  (loop []
    (let [files (retrieve-candidates db/pool)]
      (when (seq files)
        (run! process-file files)
        (recur)))))
