;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; This is an example on how it can be executed:
;; clojure -Scp $(cat classpath) -M dev/script-fix-sobjects.clj

(require
 '[app.common.logging :as l]
 '[app.common.data :as d]
 '[app.common.pprint]
 '[app.db :as db]
 '[app.storage :as sto]
 '[app.storage.impl :as impl]
 '[app.util.time :as dt]
 '[integrant.core :as ig])

;; --- HELPERS

(l/info :hint "initializing script" :args *command-line-args*)

(def noop? (some #(= % "noop") *command-line-args*))
(def chunk-size 10)

(def sql:retrieve-sobjects-chunk
  "SELECT * FROM storage_object
    WHERE created_at < ? AND deleted_at is NULL
    ORDER BY created_at desc LIMIT ?")

(defn get-chunk
  [conn cursor]
  (let [rows (db/exec! conn [sql:retrieve-sobjects-chunk cursor chunk-size])]
    [(some->> rows peek :created-at) (seq rows)]))

(defn get-candidates
  [conn]
  (->> (d/iteration (partial get-chunk conn)
                    :vf second
                    :kf first
                    :initk (dt/now))
       (sequence cat)))

(def modules
  [:app.db/pool
   :app.storage/storage
   [:app.main/default :app.worker/executor]
   [:app.main/assets :app.storage.s3/backend]
   [:app.main/assets :app.storage.fs/backend]])

(def system
  (let [config (select-keys app.main/system-config modules)
        config (-> config
                   (assoc :app.migrations/all {})
                   (assoc :app.metrics/metrics nil))]
    (ig/load-namespaces config)
    (-> config ig/prep ig/init)))

(defn update-fn
  [{:keys [conn] :as storage} {:keys [id backend] :as row}]
  (cond
    (= backend "s3")
    (do
      (l/info :hint "rename storage object backend"
              :id id
              :from-backend backend
              :to-backend :assets-s3)
      (assoc row :backend "assets-s3"))

    (= backend "assets-s3")
    (do
      (l/info :hint "ignoring storage object" :id id :backend backend)
      nil)

    (or (= backend "fs")
        (= backend "assets-fs"))
    (let [sobj (sto/row->storage-object row)
          path (-> (sto/get-object-path storage sobj) deref)]
      (l/info :hint "change storage object backend"
              :id id
              :from-backend backend
              :to-backend :assets-s3)
      (when-not noop?
        (-> (impl/resolve-backend storage :assets-s3)
            (impl/put-object sobj (sto/content path))
            (deref)))
      (assoc row :backend "assets-s3"))

    :else
    (throw (IllegalArgumentException. "unexpected backend found"))))

(try
  (db/with-atomic [conn (:app.db/pool system)]
    (let [storage (:app.storage/storage system)
          storage (assoc storage :conn conn)]
      (loop [items (get-candidates conn)]
        (when-let [item (first items)]
          (when-let [{:keys [id] :as row} (update-fn storage item)]
          (db/update! conn :storage-object (dissoc row :id) {:id (:id item)}))
          (recur (rest items))))
      (when noop?
        (throw (ex-info "explicit rollback" {})))))

  (catch Throwable cause
    (cond
      (= "explicit rollback" (ex-message cause))
      (l/warn :hint "transaction aborted")

      :else
      (l/error :hint "unexpected exception" :cause cause))))

(ig/halt! system)
(System/exit 0)
