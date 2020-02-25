;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.delete-object
  "Generic task for permanent deletion of objects."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

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

(defmethod handle-deletion :image
  [conn {:keys [id] :as props}]
  (let [sql "delete from image where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :image-collection
  [conn {:keys [id] :as props}]
  (let [sql "delete from image_collection
              where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :icon
  [conn {:keys [id] :as props}]
  (let [sql "delete from icon where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :icon-collection
  [conn {:keys [id] :as props}]
  (let [sql "delete from icon_collection
              where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :file
  [conn {:keys [id] :as props}]
  (let [sql "delete from file where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :file-image
  [conn {:keys [id] :as props}]
  (let [sql "delete from file_image where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :page
  [conn {:keys [id] :as props}]
  (let [sql "delete from page where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))

(defmethod handle-deletion :page-version
  [conn {:keys [id] :as props}]
  (let [sql "delete from page_version where id=$1 and deleted_at is not null"]
    (db/query-one conn [sql id])))
