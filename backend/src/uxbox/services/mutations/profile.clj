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
   [uxbox.tasks :as tasks]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.images :as imgs]
   [uxbox.services.queries.profile :as profile]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.storage :as ust]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.time :as tm]
   [vertx.util :as vu]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
(s/def ::lang ::us/string)
(s/def ::path ::us/string)
(s/def ::user ::us/uuid)
(s/def ::password ::us/string)
(s/def ::old-password ::us/string)


;; --- Mutation: Login

(declare retrieve-user-by-email)

(s/def ::email ::us/email)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::email ::password]
          :opt-un [::scope]))

(sm/defmutation ::login
  [{:keys [email password scope] :as params}]
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
    (-> (retrieve-user-by-email db/pool email)
        (p/then' check-user))))

(def sql:user-by-email
  "select u.*
     from users as u
    where u.email=$1
      and u.deleted_at is null")

(defn- retrieve-user-by-email
  [conn email]
  (db/query-one conn [sql:user-by-email email]))


;; --- Mutation: Add additional email
;; TODO

;; --- Mutation: Mark email as main email
;; TODO

;; --- Mutation: Verify email (or maybe query?)
;; TODO

;; --- Mutation: Update Profile (own)

(def ^:private sql:update-profile
  "update users
      set fullname = $2,
          lang = $3
    where id = $1
      and deleted_at is null
   returning *")

(defn- update-profile
  [conn {:keys [id fullname lang] :as params}]
  (let [sqlv [sql:update-profile id fullname lang]]
    (-> (db/query-one conn sqlv)
        (p/then' su/raise-not-found-if-nil)
        (p/then' profile/strip-private-attrs))))

(s/def ::update-profile
  (s/keys :req-un [::id ::fullname ::lang]))

(sm/defmutation ::update-profile
  [params]
  (db/with-atomic [conn db/pool]
    (update-profile conn params)))


;; --- Mutation: Update Password

(defn- validate-password!
  [conn {:keys [user old-password] :as params}]
  (p/let [profile (profile/retrieve-profile conn user)
          result (sodi.pwhash/verify old-password (:password profile))]
    (when-not (:valid result)
      (ex/raise :type :validation
                :code ::old-password-not-match))))

(defn update-password
  [conn {:keys [user password]}]
  (let [sql "update users
                set password = $2
              where id = $1
                and deleted_at is null
            returning id"
        password (sodi.pwhash/derive password)]
    (-> (db/query-one conn [sql user password])
        (p/then' su/raise-not-found-if-nil)
        (p/then' su/constantly-nil))))

(s/def ::update-profile-password
  (s/keys :req-un [::user ::password ::old-password]))

(sm/defmutation ::update-profile-password
  [params]
  (db/with-atomic [conn db/pool]
    (validate-password! conn params)
    (update-password conn params)))


;; --- Mutation: Update Photo

(declare upload-photo)
(declare update-profile-photo)

(s/def ::file ::imgs/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::user ::file]))

(sm/defmutation ::update-profile-photo
  [{:keys [user file] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [profile (profile/retrieve-profile conn user)
            photo (upload-photo conn params)]

      ;; Schedule deletion of old photo
      (tasks/schedule! conn {:name "remove-media"
                             :props {:path (:photo profile)}})
      ;; Save new photo
      (update-profile-photo conn user photo))))

(defn- upload-photo
  [conn {:keys [file user]}]
  (when-not (imgs/valid-image-types? (:mtype file))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))
  (vu/blocking
   (let [thumb-opts {:width 256
                     :height 256
                     :quality 75
                     :format "webp"}
         prefix (-> (sodi.prng/random-bytes 8)
                    (sodi.util/bytes->b64s))
         name   (str prefix ".webp")
         photo  (images/generate-thumbnail2 (fs/path (:path file)) thumb-opts)]
     (ust/save! media/media-storage name photo))))

(defn- update-profile-photo
  [conn user path]
  (let [sql "update users set photo=$1 where id=$2 and deleted_at is null returning id"]
    (-> (db/query-one conn [sql (str path) user])
        (p/then' su/raise-not-found-if-nil))))


;; --- Mutation: Register Profile

(declare check-profile-existence!)
(declare register-profile)

(s/def ::register-profile
  (s/keys :req-un [::email ::password ::fullname]))

(sm/defmutation ::register-profile
  [params]
  (when-not (:registration-enabled cfg/config)
    (ex/raise :type :restriction
              :code :registration-disabled))
  (db/with-atomic [conn db/pool]
    (check-profile-existence! conn params)
    (register-profile conn params)))

(def ^:private sql:insert-user
  "insert into users (id, fullname, email, password, photo)
   values ($1, $2, $3, $4, '') returning *")

(def ^:private sql:insert-email
  "insert into user_emails (user_id, email, is_main)
   values ($1, $2, true)")

(def ^:private sql:profile-existence
  "select exists (select * from users
                   where email = $1
                     and deleted_at is null) as val")

(defn- check-profile-existence!
  [conn {:keys [email] :as params}]
  (-> (db/query-one conn [sql:profile-existence email])
      (p/then' (fn [result]
                 (when (:val result)
                   (ex/raise :type :validation
                             :code ::email-already-exists))
                 params))))

(defn create-profile
  "Create the user entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [id fullname email password] :as params}]
  (let [id (or id (uuid/next))
        password (sodi.pwhash/derive password)
        sqlv1 [sql:insert-user
               id
               fullname
               email
               password]
        sqlv2 [sql:insert-email id email]]
    (p/let [profile (db/query-one conn sqlv1)]
      (db/query-one conn sqlv2)
      profile)))

(defn register-profile
  [conn params]
  (-> (create-profile conn params)
      (p/then' profile/strip-private-attrs)
      (p/then (fn [profile]
                  ;; TODO: send a correct link for email verification
                (let [data {:to (:email params)
                            :name (:fullname params)}]
                  (p/do!
                   (emails/send! conn emails/register data)
                   profile))))))

;; --- Mutation: Request Profile Recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(def sql:insert-recovery-token
  "insert into tokens (user_id, token) values ($1, $2)")

(sm/defmutation ::request-profile-recovery
  [{:keys [email] :as params}]
  (letfn [(create-recovery-token [conn {:keys [id] :as user}]
            (let [token (-> (sodi.prng/random-bytes 32)
                            (sodi.util/bytes->b64s))
                  sql sql:insert-recovery-token]
              (-> (db/query-one conn [sql id token])
                  (p/then (constantly (assoc user :token token))))))
          (send-email-notification [conn user]
            (emails/send! conn
                          emails/password-recovery
                          {:to (:email user)
                           :token (:token user)
                           :name (:fullname user)}))]
    (db/with-atomic [conn db/pool]
      (-> (retrieve-user-by-email conn email)
          (p/then' su/raise-not-found-if-nil)
          (p/then #(create-recovery-token conn %))
          (p/then #(send-email-notification conn %))
          (p/then (constantly nil))))))

;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(def sql:remove-recovery-token
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


