;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.telemetry
  (:require
   [clojure.tools.logging :as log]
   [app.common.spec :as us]
   [app.db :as db]
   [app.http.middleware :refer [wrap-parse-request-body wrap-errors]]
   [promesa.exec :as px]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Migrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:create-info-table
  "CREATE TABLE telemetry.info (
     instance_id uuid,
     created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
     data jsonb NOT NULL,

     PRIMARY KEY (instance_id, created_at)
  ) PARTITION BY RANGE(created_at);

  CREATE TABLE telemetry.info_default (LIKE telemetry.info INCLUDING ALL);

  ALTER TABLE telemetry.info
    ATTACH PARTITION telemetry.info_default DEFAULT;")

;; Research on this
;; ALTER TABLE telemetry.instance_info
;;   SET (autovacuum_freeze_min_age = 0,
;;        autovacuum_freeze_max_age = 100000);")

(def sql:create-instance-table
  "CREATE TABLE IF NOT EXISTS telemetry.instance (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
  );")

(def migrations
  [{:name "0001-add-telemetry-schema"
    :fn #(db/exec! % ["CREATE SCHEMA IF NOT EXISTS telemetry;"])}

   {:name "0002-add-instance-table"
    :fn #(db/exec! % [sql:create-instance-table])}

   {:name "0003-add-info-table"
    :fn #(db/exec! % [sql:create-info-table])}])

(defmethod ig/init-key ::migrations [_ _] migrations)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Router Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handler)
(declare process-request)

(defmethod ig/init-key ::handler
  [_ cfg]
  (-> (partial handler cfg)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-parse-request-body)))

(s/def ::instance-id ::us/uuid)
(s/def ::params (s/keys :req-un [::instance-id]))

(defn handler
  [{:keys [executor] :as cfg} {:keys [params] :as request}]
  (try
    (let [params (us/conform ::params params)
          cfg    (assoc cfg
                        :instance-id (:instance-id params)
                        :data (dissoc params :instance-id))]
      (px/run! executor (partial process-request cfg)))
    (catch Exception e
      (log/errorf e "Unexpected error.")))
  {:status 200
   :body "OK\n"})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Request Processing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:insert-instance-info
  "insert into telemetry.instance_info (instance_id, data, created_at)
   values (?, ?, date_trunc('day', now()))
       on conflict (instance_id, created_at)
       do update set data = ?")

(defn- process-request
  [{:keys [pool instance-id data]}]
  (try
    (db/with-atomic [conn pool]
      (let [data (db/json data)]
        (db/exec! conn [sql:insert-instance-info
                        instance-id
                        data
                        data])))
    (catch Exception e
      (log/errorf e "Error on procesing request."))))
