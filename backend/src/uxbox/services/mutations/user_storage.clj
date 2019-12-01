;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.user-storage
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.services.queries.user-storage :refer [decode-row]]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

;; --- Update

(s/def ::user ::us/uuid)
(s/def ::key ::us/string)
(s/def ::val any?)

(s/def ::upsert-user-storage-entry
  (s/keys :req-un [::key ::val ::user]))

(sm/defmutation ::upsert-user-storage-entry
  [{:keys [key val user] :as params}]
  (let [sql "insert into user_storage (key, val, user_id)
             values ($1, $2, $3)
                 on conflict (user_id, key)
                 do update set val = $2"
        val (blob/encode val)]
    (-> (db/query-one db/pool [sql key val user])
        (p/then' su/constantly-nil))))

;; --- Delete KVStore

(s/def ::delete-user-storage-entry
  (s/keys :req-un [::key ::user]))

(sm/defmutation ::delete-user-storage-entry
  [{:keys [user key] :as params}]
  (let [sql "delete from user_storage
              where user_id = $2
                and key = $1"]
    (-> (db/query-one db/pool [sql key user])
        (p/then' su/constantly-nil))))
