;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.tokens
  (:require
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [sodi.prng]
   [sodi.util]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.time :as dt]
   [app.db :as db]))

(defn next-token
  ([] (next-token 96))
  ([n]
   (-> (sodi.prng/random-nonce n)
       (sodi.util/bytes->b64s))))

(def default-duration
  (dt/duration {:hours 48}))

(defn- decode-row
  [{:keys [content] :as row}]
  (when row
    (cond-> row
      (db/pgobject? content)
      (assoc :content (db/decode-transit-pgobject content)))))

(defn create!
  ([conn payload] (create! conn payload {}))
  ([conn payload {:keys [valid] :or {valid default-duration}}]
   (let [token (next-token)
         until (dt/plus (dt/now) (dt/duration valid))]
     (db/insert! conn :generic-token
                 {:content (db/tjson payload)
                  :token token
                  :valid-until until})
     token)))

(defn delete!
  [conn token]
  (db/delete! conn :generic-token {:token token}))

(defn retrieve
  ([conn token] (retrieve conn token {}))
  ([conn token {:keys [delete] :or {delete false}}]
   (let [row (->> (db/query conn :generic-token {:token token})
                  (map decode-row)
                  (first))]

     (when-not row
       (ex/raise :type :validation
                 :code ::invalid-token))

     ;; Validate the token expiration
     (when (> (inst-ms (dt/now))
              (inst-ms (:valid-until row)))
       (ex/raise :type :validation
                 :code ::invalid-token))

     (when delete
       (db/delete! conn :generic-token {:token token}))

     (-> row
         (dissoc :content)
         (merge (:content row))))))



