;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.kvstore
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.core :as sv]
   [uxbox.util.blob :as blob]
   [uxbox.util.data :as data]
   [uxbox.util.spec :as us]
   [uxbox.util.time :as dt]
   [uxbox.util.uuid :as uuid]))

(defn- decode-row
  [{:keys [value] :as row}]
  (when row
    (cond-> row
      value (assoc :value (blob/decode value)))))

;; --- Update KVStore

(s/def ::user ::us/uuid)
(s/def ::key ::us/string)
(s/def ::value any?)

(s/def ::upsert-kvstore
  (s/keys :req-un [::key ::value ::user]))

(sv/defmutation :upsert-kvstore
  {:doc "Update or insert kvstore entry."
   :spec ::upsert-kvstore}
  [{:keys [key value user] :as params}]
  (let [sql "insert into kvstore (key, value, user_id)
             values ($1, $2, $3)
                 on conflict (user_id, key)
                 do update set value = $2"
        val (blob/encode value)]
    (-> (db/query-one db/pool [sql key val user])
        (p/then' sv/constantly-nil))))

;; --- Retrieve KVStore

(s/def ::kvstore-entry
  (s/keys :req-un [::key ::user]))

(sv/defquery :kvstore-entry
  {:doc "Retrieve kvstore entry."
   :spec ::kvstore-entry}
  [{:keys [key user]}]
  (let [sql "select kv.*
               from kvstore as kv
              where kv.user_id = $2
                and kv.key = $1"]
    (-> (db/query-one db/pool [sql key user])
        (p/then' sv/raise-not-found-if-nil)
        (p/then' decode-row))))

;; --- Delete KVStore

(s/def ::delete-kvstore
  (s/keys :req-un [::key ::user]))

(sv/defmutation :delete-kvstore
  {:doc "Delete kvstore entry."
   :spec ::delete-kvstore}
  [{:keys [user key] :as params}]
  (let [sql "delete from kvstore
              where user_id = $2
                and key = $1"]
    (-> (db/query-one db/pool [sql key user])
        (p/then' sv/constantly-nil))))
