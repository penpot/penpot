;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.file-media-gc
  "A maintenance task that is responsible to purge the unused media
  objects from files. A file is eligible to be garbage collected
  after some period of inactivity (the default threshold is 72h)."
  (:require
   [app.common.logging :as l]
   [app.common.pages.migrations :as pmg]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare process-file)
(declare retrieve-candidates)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::max-age]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [_]
    (db/with-atomic [conn pool]
      (let [cfg (assoc cfg :conn conn)]
        (loop [n 0]
          (let [files (retrieve-candidates cfg)]
            (if (seq files)
              (do
                (run! (partial process-file cfg) files)
                (recur (+ n (count files))))
              (do
                (l/debug :msg "finished processing files" :processed n)
                {:processed n}))))))))

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
         (mapv (fn [{:keys [age] :as row}]
                 (assoc row :age (dt/duration {:seconds age})))))))

(def ^:private
  collect-media-xf
  (comp
   (map :objects)
   (mapcat vals)
   (map (fn [{:keys [type] :as obj}]
          (case type
            :path (get-in obj [:fill-image :id])
            :image (get-in obj [:metadata :id])
            nil)))
   (filter uuid?)))

(defn- collect-used-media
  [data]
  (let [pages (concat
               (vals (:pages-index data))
               (vals (:components data)))]
  (-> #{}
      (into collect-media-xf pages)
      (into (keys (:media data))))))

(defn- process-file
  [{:keys [conn] :as cfg} {:keys [id data age] :as file}]
  (let [data   (-> (blob/decode data)
                   (assoc :id id)
                   (pmg/migrate-data))

        used   (collect-used-media data)
        unused (->> (db/query conn :file-media-object {:file-id id})
                    (remove #(contains? used (:id %))))]

    (l/debug :action "processing file"
             :id id
             :age age
             :to-delete (count unused))

    ;; Mark file as trimmed
    (db/update! conn :file
                {:has-media-trimmed true}
                {:id id})

    (doseq [mobj unused]
      (l/debug :action "deleting media object"
               :id (:id mobj)
               :media-id (:media-id mobj)
               :thumbnail-id (:thumbnail-id mobj))

      ;; NOTE: deleting the file-media-object in the database
      ;; automatically marks as touched the referenced storage
      ;; objects. The touch mechanism is needed because many files can
      ;; point to the same storage objects and we can't just delete
      ;; them.
      (db/delete! conn :file-media-object {:id (:id mobj)}))

    nil))
