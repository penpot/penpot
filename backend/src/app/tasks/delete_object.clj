;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.delete-object
  "Generic task for permanent deletion of objects."
  (:require
   [app.common.spec :as us]
   [integrant.core :as ig]
   [app.db :as db]
   [app.metrics :as mtx]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]))

(declare handler)
(declare handle-deletion)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::mtx/metrics]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (let [handler #(handler cfg %)]
    (->> {:registry (:registry metrics)
          :type :summary
          :name "task_delete_object_timing"
          :help "delete object task timing"}
         (mtx/instrument handler))))

(s/def ::type ::us/keyword)
(s/def ::id ::us/uuid)
(s/def ::props (s/keys :req-un [::id ::type]))

(defn- handler
  [{:keys [pool]} {:keys [props] :as task}]
  (us/verify ::props props)
  (db/with-atomic [conn pool]
    (handle-deletion conn props)))

(defmulti handle-deletion (fn [_ props] (:type props)))

(defmethod handle-deletion :default
  [_conn {:keys [type]}]
  (log/warn "no handler found for" type))

(defmethod handle-deletion :file
  [conn {:keys [id] :as props}]
  (let [sql "delete from file where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :project
  [conn {:keys [id] :as props}]
  (let [sql "delete from project where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :media-object
  [conn {:keys [id] :as props}]
  (let [sql "delete from media_object where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))
