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
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.storage :as ust]))

(s/def ::type keyword?)
(s/def ::id ::us/uuid)

(s/def ::props
  (s/keys :req-un [::id ::type]))

(defmulti handle-deletion (fn [conn props] (:type props)))

(defmethod handle-deletion :default
  [conn {:keys [type id] :as props}]
  (log/warn "no handler found for" type))

(defn handler
  [{:keys [props] :as task}]
  (us/verify ::props props)
  (db/with-atomic [conn db/pool]
    (handle-deletion conn props)))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__delete_object"
  :help "Timing of remove-object task."})

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

(defmethod handle-deletion :color
  [conn {:keys [id] :as props}]
  (let [sql "delete from color where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :page
  [conn {:keys [id] :as props}]
  (let [sql "delete from page where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))
