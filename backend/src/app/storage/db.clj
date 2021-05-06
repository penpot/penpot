;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage.db
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.storage.impl :as impl]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   java.io.ByteArrayInputStream))

;; --- BACKEND INIT

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt-un [::db/pool]))

(defmethod ig/init-key ::backend
  [_ cfg]
  (assoc cfg :type :db))

(s/def ::type ::us/keyword)
(s/def ::backend
  (s/keys :req-un [::type ::db/pool]))

;; --- API IMPL

(defmethod impl/put-object :db
  [{:keys [conn] :as storage} {:keys [id] :as object} content]
  (let [data (impl/slurp-bytes content)]
    (db/insert! conn :storage-data {:id id :data data})
    object))

(defmethod impl/copy-object :db
  [{:keys [conn] :as storage} src-object dst-object]
  (db/exec-one! conn ["insert into storage_data (id, data) select ? as id, data from storage_data  where id=?"
                      (:id dst-object)
                      (:id src-object)]))

(defmethod impl/get-object-data :db
  [{:keys [conn] :as backend} {:keys [id] :as object}]
  (let [result (db/exec-one! conn ["select data from storage_data where id=?" id])]
    (ByteArrayInputStream. (:data result))))

(defmethod impl/get-object-url :db
  [_ _]
  (throw (UnsupportedOperationException. "not supported")))

(defmethod impl/del-objects-in-bulk :db
  [_ _]
  ;; NOOP: because deleting the row already deletes the file data from
  ;; the database.
  nil)
