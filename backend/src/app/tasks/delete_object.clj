;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

;; TODO: DEPRECATED
;; Should be removed in the 1.8.x

(ns app.tasks.delete-object
  "Generic task for permanent deletion of objects."
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.db :as db]
   [app.storage :as sto]
   [app.util.logging :as l]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare handle-deletion)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::sto/storage]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [{:keys [props] :as task}]
    (us/verify ::props props)
    (db/with-atomic [conn pool]
      (let [cfg (assoc cfg :conn conn)]
        (handle-deletion cfg props)))))

(s/def ::type ::us/keyword)
(s/def ::id ::us/uuid)
(s/def ::props (s/keys :req-un [::id ::type]))

(defmulti handle-deletion
  (fn [_ props] (:type props)))

(defmethod handle-deletion :default
  [_cfg {:keys [type]}]
  (l/warn :hint "no handler found"
          :type (d/name type)))

(defmethod handle-deletion :file
  [{:keys [conn]} {:keys [id] :as props}]
  (let [sql "delete from file where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :project
  [{:keys [conn]} {:keys [id] :as props}]
  (let [sql "delete from project where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :team
  [{:keys [conn]} {:keys [id] :as props}]
  (let [sql "delete from team where id=? and deleted_at is not null"]
    (db/exec-one! conn [sql id])))

(defmethod handle-deletion :team-font-variant
  [{:keys [conn storage]} {:keys [id] :as props}]
  (let [font    (db/get-by-id conn :team-font-variant id {:check-not-found false})
        storage (assoc storage :conn conn)]
    (when (:deleted-at font)
      (db/delete! conn :team-font-variant {:id id})
      (some->> (:woff1-file-id font) (sto/del-object storage))
      (some->> (:woff2-file-id font) (sto/del-object storage))
      (some->> (:otf-file-id font)   (sto/del-object storage))
      (some->> (:ttf-file-id font)   (sto/del-object storage)))))
