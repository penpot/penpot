;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.user-storage
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

(defn decode-row
  [{:keys [val] :as row}]
  (when row
    (cond-> row
      val (assoc :val (blob/decode val)))))

(s/def ::user-storage-item
  (s/keys :req-un [::key ::user]))

(sq/defquery ::user-storage-entry
  [{:keys [key user]}]
  (let [sql "select kv.*
               from user_storage as kv
              where kv.user_id = $2
                and kv.key = $1"]
    (-> (db/query-one db/pool [sql key user])
        (p/then' su/raise-not-found-if-nil)
        (p/then' decode-row))))
