;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

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

(defn- initialize-secret-key!
  [{:keys [conn] :as cfg}]
  (let [key (-> (bn/random-bytes 64)
                (bc/bytes->b64u)
                (bc/bytes->str))]
    (db/exec-one! conn ["insert into server_prop (id, content)
                         values ('secret-key', ?) on conflict do nothing"
                        (db/tjson key)])))

(defn- initialize-instance-id!
  [{:keys [conn] :as cfg}]
  (let [iid (uuid/random)]
    (db/exec-one! conn ["insert into server_prop (id, content)
                         values ('instance-id', ?::jsonb) on conflict do nothing"
                        (db/tjson iid)])))

(defn- retrieve-all
  [{:keys [conn] :as cfg}]
  (reduce (fn [acc row]
            (assoc acc (keyword (:id row)) (db/decode-transit-pgobject (:content row))))
          {}
          (db/exec! conn ["select * from server_prop;"])))
