;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.file-offload
  "A maintenance task that offloads file data to an external storage (S3)."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.storage :as sto]
   [app.storage.impl :as simpl]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(def sql:offload-candidates-chunk
  "select f.id, f.data from file as f
    where f.data is not null
      and f.modified_at < now() - ?::interval
    order by f.modified_at
    limit 10")

(defn- retrieve-candidates
  [{:keys [conn max-age]}]
  (db/exec! conn [sql:offload-candidates-chunk max-age]))

(defn- offload-candidate
  [{:keys [storage conn backend] :as cfg} {:keys [id data] :as file}]
  (l/debug :action "offload file data" :id id)
  (let [backend (simpl/resolve-backend storage backend)]
    (->> (simpl/content data)
         (simpl/put-object backend file))
    (db/update! conn :file
                {:data nil
                 :data-backend (name (:id backend))}
                {:id id})))

;; ---- STATE INIT

(s/def ::max-age ::dt/duration)
(s/def ::backend ::us/keyword)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::max-age ::sto/storage ::backend]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool max-age] :as cfg}]
  (fn [_]
    (db/with-atomic [conn pool]
      (let [max-age (db/interval max-age)
            cfg     (-> cfg
                        (assoc :conn conn)
                        (assoc :max-age max-age))]
        (loop [n 0]
          (let [candidates (retrieve-candidates cfg)]
            (if (seq candidates)
              (do
                (run! (partial offload-candidate cfg) candidates)
                (recur (+ n (count candidates))))
              (l/debug :hint "offload summary" :count n))))))))
