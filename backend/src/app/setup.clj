;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.setup
  "Initial data setup of instance."
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare initialize-instance-id!)
(declare initialize-secret-key!)
(declare retrieve-all)

(defmethod ig/pre-init-spec ::props [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::props
  [_ {:keys [pool] :as cfg}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)]
      (initialize-secret-key! cfg)
      (initialize-instance-id! cfg)
      (retrieve-all cfg))))

(def sql:upsert-secret-key
  "insert into server_prop (id, preload, content)
   values ('secret-key', true, ?::jsonb)
   on conflict (id) do update set content = ?::jsonb")

(def sql:insert-secret-key
  "insert into server_prop (id, preload, content)
   values ('secret-key', true, ?::jsonb)
   on conflict (id) do nothing")

(defn- initialize-secret-key!
  [{:keys [conn key] :as cfg}]
  (if key
    (let [key (db/tjson key)]
      (db/exec-one! conn [sql:upsert-secret-key key key]))
    (let [key (-> (bn/random-bytes 64)
                  (bc/bytes->b64u)
                  (bc/bytes->str))
          key (db/tjson key)]
      (db/exec-one! conn [sql:insert-secret-key key]))))

(defn- initialize-instance-id!
  [{:keys [conn] :as cfg}]
  (let [iid (uuid/random)]

    (db/insert! conn :server-prop
                {:id "instance-id"
                 :preload true
                 :content (db/tjson iid)}
                {:on-conflict-do-nothing true})))

(defn- retrieve-all
  [{:keys [conn] :as cfg}]
  (reduce (fn [acc row]
            (assoc acc (keyword (:id row)) (db/decode-transit-pgobject (:content row))))
          {}
          (db/query conn :server-prop {:preload true})))
