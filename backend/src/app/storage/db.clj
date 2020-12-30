;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.storage.db
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.storage.impl :as impl]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [lambdaisland.uri :as u]
   [integrant.core :as ig])
  (:import
   org.postgresql.largeobject.LargeObject
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.InputStream
   java.io.OutputStream))

;; --- BACKEND INIT

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt-un [::db/pool]))

(defmethod ig/init-key ::backend
  [_ cfg]
  (assoc cfg :type :db))

(s/def ::type #{:db})
(s/def ::backend
  (s/keys :req-un [::type ::db/pool]))

;; --- API IMPL

(defmethod impl/put-object :db
  [{:keys [conn] :as storage} {:keys [id] :as object} content]
  (let [data (impl/slurp-bytes content)]
    (db/insert! conn :storage-data {:id id :data data})
    object))

(defmethod impl/get-object :db
  [{:keys [conn] :as backend} {:keys [id] :as object}]
  (let [result (db/exec-one! conn ["select data from storage_data where id=?" id])]
    (ByteArrayInputStream. (:data result))))

(defmethod impl/get-object-url :db
  [backend {:keys [id] :as object}]
  (throw (UnsupportedOperationException. "not supported")))

(defmethod impl/del-objects-in-bulk :db
  [backend ids]
  ;; NOOP: because delting the row already deletes the file data from
  ;; the database.
  nil)
