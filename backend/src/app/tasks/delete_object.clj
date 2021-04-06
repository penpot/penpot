;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.delete-object
  "Generic task for permanent deletion of objects."
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.db :as db]
   [app.util.logging :as l]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare handle-deletion)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [{:keys [props] :as task}]
    (us/verify ::props props)
    (db/with-atomic [conn pool]
      (handle-deletion conn props))))

(s/def ::type ::us/keyword)
(s/def ::id ::us/uuid)
(s/def ::props (s/keys :req-un [::id ::type]))

(defmulti handle-deletion
  (fn [_ props] (:type props)))

(defmethod handle-deletion :default
  [_conn {:keys [type]}]
  (l/warn :hint "no handler found"
          :type (d/name type)))

(defmethod handle-deletion :file
  [conn {:keys [id] :as props}]
  (let [sql "delete from file where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :project
  [conn {:keys [id] :as props}]
  (let [sql "delete from project where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :team
  [conn {:keys [id] :as props}]
  (let [sql "delete from team where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))
