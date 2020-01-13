;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.profile
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [datoteka.storages :as ds]
   [promesa.core :as p]
   [promesa.exec :as px]
   [sodi.prng]
   [sodi.pwhash]
   [sodi.util]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.emails :as emails]
   [uxbox.images :as images]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.services.queries.users :refer [get-profile
                                         decode-profile-row
                                         strip-private-attrs
                                         resolve-thumbnail]]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [vertx.core :as vc]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
(s/def ::metadata any?)
(s/def ::old-password ::us/string)
(s/def ::password ::us/string)
(s/def ::path ::us/string)
(s/def ::user ::us/uuid)
(s/def ::username ::us/string)

;; --- Utilities

(su/defstr sql:user-by-username-or-email
  "select u.*
     from users as u
    where u.username=$1 or u.email=$1
      and u.deleted_at is null")

(defn- retrieve-user
  [conn username]
  (db/query-one conn [sql:user-by-username-or-email username]))

;; --- Mutation: Login

(s/def ::username ::us/string)
(s/def ::password ::us/string)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::username ::password]
          :opt-un [::scope]))

(sm/defmutation ::login
  [{:keys [username password scope] :as params}]
  (letfn [(check-password [user password]
            (let [result (sodi.pwhash/verify password (:password user))]
              (:valid result)))

          (check-user [user]
            (when-not user
              (ex/raise :type :validation
                        :code ::wrong-credentials))
            (when-not (check-password user password)
              (ex/raise :type :validation
                        :code ::wrong-credentials))

            {:id (:id user)})]
    (-> (retrieve-user db/pool username)
        (p/then' check-user))))

;; --- Mutation: Update Profile (own)

(defn- check-username-and-email!
  [conn {:keys [id username email] :as params}]
  (let [sql1 "select exists
                (select * from users
                  where username = $2
                    and id != $1
                  ) as val"
        sql2 "select exists
                (select * from users
                  where email = $2
                    and id != $1
                  ) as val"]
    (p/let [res1 (db/query-one conn [sql1 id username])
            res2 (db/query-one conn [sql2 id email])]
      (when (:val res1)
        (ex/raise :type :validation
                  :code ::username-already-exists))
      (when (:val res2)
        (ex/raise :type :validation
                  :code ::email-already-exists))
      params)))

(su/defstr sql:update-profile
  "update users
      set username = $2,
          email = $3,
          fullname = $4,
          metadata = $5
    where id = $1
      and deleted_at is null
   returning *")

(defn- update-profile
  [conn {:keys [id username email fullname metadata] :as params}]
  (let [sqlv [sql:update-profile id username
              email fullname (blob/encode metadata)]]
    (-> (db/query-one conn sqlv)
        (p/then' su/raise-not-found-if-nil)
        (p/then' decode-profile-row)
        (p/then' strip-private-attrs))))

(s/def ::update-profile
  (s/keys :req-un [::id ::username ::email ::fullname ::metadata]))

(sm/defmutation ::update-profile
  [params]
  (db/with-atomic [conn db/pool]
    (-> (p/resolved params)
        (p/then (partial check-username-and-email! conn))
        (p/then (partial update-profile conn)))))

;; --- Mutation: Update Password

(defn- validate-password
  [conn {:keys [user old-password] :as params}]
  (p/let [profile (get-profile conn user)
          result (sodi.pwhash/verify old-password (:password profile))]
    (when-not (:valid result)
      (ex/raise :type :validation
                :code ::old-password-not-match))
    params))

(defn update-password
  [conn {:keys [user password]}]
  (let [sql "update users
                set password = $2
              where id = $1
                and deleted_at is null
            returning id"]
    (-> (db/query-one conn [sql user password])
        (p/then' su/raise-not-found-if-nil)
        (p/then' su/constantly-nil))))

(s/def ::update-password
  (s/keys :req-un [::user ::us/password ::old-password]))

(sm/defmutation :update-password
  {:doc "Update self password."
   :spec ::update-password}
  [params]
  (db/with-atomic [conn db/pool]
    (-> (p/resolved params)
        (p/then (partial validate-password conn))
        (p/then (partial update-password conn)))))

;; --- Mutation: Update Photo

(s/def :uxbox$upload/name ::us/string)
(s/def :uxbox$upload/size ::us/integer)
(s/def :uxbox$upload/mtype ::us/string)
(s/def ::upload
  (s/keys :req-un [:uxbox$upload/name
                   :uxbox$upload/size
                   :uxbox$upload/mtype]))

(s/def ::file ::upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::user ::file]))

(def valid-image-types?
  #{"image/jpeg", "image/png", "image/webp"})

(sm/defmutation ::update-profile-photo
  [{:keys [user file] :as params}]
  (letfn [(store-photo [{:keys [name path] :as upload}]
            (let [filename (fs/name name)
                  storage media/images-storage]
              (-> (ds/save storage filename path)
                  #_(su/handle-on-context))))

          (update-user-photo [path]
            (let [sql "update users
                          set photo = $1
                        where id = $2
                          and deleted_at is null
                       returning id, photo"]
              (-> (db/query-one db/pool [sql (str path) user])
                  (p/then' su/raise-not-found-if-nil)
                  ;; (p/then' strip-private-attrs)
                  (p/then resolve-thumbnail))))]

    (when-not (valid-image-types? (:mtype file))
      (ex/raise :type :validation
                :code :image-type-not-allowed
                :hint "Seems like you are uploading an invalid image."))

    (-> (store-photo file)
        (p/then update-user-photo))))

;; --- Mutation: Register Profile

(def ^:private create-user-sql
  "insert into users (id, fullname, username, email, password, metadata, photo)
   values ($1, $2, $3, $4, $5, $6, '') returning *")

(defn- check-profile-existence!
  [conn {:keys [username email] :as params}]
  (let [sql "select exists
               (select * from users
                 where username = $1
                   or  email = $2
                 ) as val"]
    (-> (db/query-one conn [sql username email])
        (p/then (fn [result]
                  (when (:val result)
                    (ex/raise :type :validation
                              :code ::username-or-email-already-exists))
                  params)))))

(defn create-profile
  "Create the user entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [id username fullname email password metadata] :as params}]
  (let [id (or id (uuid/next))
        metadata (blob/encode metadata)
        password (sodi.pwhash/derive password)
        sqlv [create-user-sql
              id
              fullname
              username
              email
              password
              metadata]]
    (-> (db/query-one conn sqlv)
        (p/then' decode-profile-row))))

(defn register-profile
  [conn params]
  (-> (create-profile conn params)
      (p/then' strip-private-attrs)
      (p/then (fn [profile]
                (-> (emails/send! emails/register {:to (:email params)
                                                   :name (:fullname params)})
                    (p/then' (constantly profile)))))))

(s/def ::register-profile
  (s/keys :req-un [::username ::email ::password ::fullname]))

(sm/defmutation ::register-profile
  [params]
  (when-not (:registration-enabled cfg/config)
    (ex/raise :type :restriction
              :code :registration-disabled))
  (db/with-atomic [conn db/pool]
    (-> (p/resolved params)
        (p/then (partial check-profile-existence! conn))
        (p/then (partial register-profile conn)))))

;; --- Mutation: Request Profile Recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::username]))

(su/defstr sql:insert-recovery-token
  "insert into tokens (user_id, token) values ($1, $2)")

(sm/defmutation ::request-profile-recovery
  [{:keys [username] :as params}]
  (letfn [(create-recovery-token [conn {:keys [id] :as user}]
            (let [token (-> (sodi.prng/random-bytes 32)
                            (sodi.util/bytes->b64s))
                  sql sql:insert-recovery-token]
              (-> (db/query-one conn [sql id token])
                  (p/then (constantly (assoc user :token token))))))
          (send-email-notification [conn user]
            (emails/send! emails/password-recovery
                          {:to (:email user)
                           :token (:token user)
                           :name (:fullname user)}))]
    (db/with-atomic [conn db/pool]
      (-> (retrieve-user conn username)
          (p/then' su/raise-not-found-if-nil)
          (p/then #(create-recovery-token conn %))
          (p/then #(send-email-notification conn %))
          (p/then (constantly nil))))))

;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(su/defstr sql:remove-recovery-token
  "delete from tokenes where user_id=$1 and token=$2")

(sm/defmutation ::recover-profile
  [{:keys [token password]}]
  (letfn [(validate-token [conn token]
            (let [sql "delete from tokens where token=$1 returning *"
                  sql "select * from tokens where token=$1"]
              (-> (db/query-one conn [sql token])
                  (p/then' :user-id)
                  (p/then' su/raise-not-found-if-nil))))
          (update-password [conn user-id]
            (let [sql "update users set password=$2 where id=$1"
                  pwd (sodi.pwhash/derive password)]
              (-> (db/query-one conn [sql user-id pwd])
                  (p/then' (constantly nil)))))]
    (db/with-atomic [conn db/pool]
      (-> (validate-token conn token)
          (p/then (fn [user-id] (update-password conn user-id)))))))
