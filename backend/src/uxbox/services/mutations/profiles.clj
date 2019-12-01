;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.profiles
  (:require
   [buddy.hashers :as hashers]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [datoteka.storages :as ds]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.emails :as emails]
   [uxbox.images :as images]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.services.queries.profiles :refer [get-profile
                                            decode-profile-row
                                            strip-private-attrs
                                            resolve-thumbnail]]
   [uxbox.util.blob :as blob]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.spec :as us]
   [uxbox.util.token :as token]
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

(defn- update-profile
  [conn {:keys [id username email fullname metadata] :as params}]
  (let [sql "update users
                set username = $2,
                    email = $3,
                    fullname = $4,
                    metadata = $5
              where id = $1
                and deleted_at is null
             returning *"]
    (-> (db/query-one conn [sql id username email fullname (blob/encode metadata)])
        (p/then' su/raise-not-found-if-nil)
        (p/then' decode-profile-row)
        (p/then' strip-private-attrs))))

(s/def ::update-profile
  (s/keys :req-un [::id ::username ::email ::fullname ::metadata]))

(sm/defmutation :update-profile
  {:doc "Update self profile."
   :spec ::update-profile}
  [params]
  (db/with-atomic [conn db/pool]
    (-> (p/resolved params)
        (p/then (partial check-username-and-email! conn))
        (p/then (partial update-profile conn)))))

;; --- Mutation: Update Password

(defn- validate-password
  [conn {:keys [user old-password] :as params}]
  (p/let [profile (get-profile conn user)]
    (when-not (hashers/check old-password (:password profile))
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

(s/def ::file ::us/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::user ::file]))

(def valid-image-types?
  #{"image/jpeg", "image/png", "image/webp"})

(sm/defmutation :update-profile-photo
  {:doc "Update profile photo."
   :spec ::update-profile-photo}
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
                       returning *"]
              (-> (db/query-one db/pool [sql (str path) user])
                  (p/then' su/raise-not-found-if-nil)
                  (p/then' strip-private-attrs)
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
        password (hashers/encrypt password)
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
      #_(p/then (fn [profile]
                  (-> (emails/send! {::emails/id :users/register
                                     ::emails/to (:email params)
                                     ::emails/priority :high
                                     :name (:fullname params)})
                      (p/then' (constantly profile)))))))

(s/def ::register-profile
  (s/keys :req-un [::username ::email ::password ::fullname]))

(sm/defmutation :register-profile
  {:doc "Register new user."
   :spec ::register-profile}
  [params]
  (when-not (:registration-enabled cfg/config)
    (ex/raise :type :restriction
              :code :registration-disabled))
  (db/with-atomic [conn db/pool]
    (-> (p/resolved params)
        (p/then (partial check-profile-existence! conn))
        (p/then (partial register-profile conn)))))

;; --- Password Recover

;; (defn- recovery-token-exists?
;;   "Checks if the token exists in the system. Just
;;   return `true` or `false`."
;;   [conn token]
;;   (let [sqlv (sql/recovery-token-exists? {:token token})
;;         result (db/fetch-one conn sqlv)]
;;     (:token_exists result)))

;; (defn- retrieve-user-for-recovery-token
;;   "Retrieve a user id (uuid) for the given token. If
;;   no user is found, an exception is raised."
;;   [conn token]
;;   (let [sqlv (sql/get-recovery-token {:token token})
;;         data (db/fetch-one conn sqlv)]
;;     (or (:user data)
;;         (ex/raise :type :validation
;;                   :code ::invalid-token))))

;; (defn- mark-token-as-used
;;   [conn token]
;;   (let [sqlv (sql/mark-recovery-token-used {:token token})]
;;     (pos? (db/execute conn sqlv))))

;; (defn- recover-password
;;   "Given a token and password, resets the password
;;   to corresponding user or raise an exception."
;;   [conn {:keys [token password]}]
;;   (let [user (retrieve-user-for-recovery-token conn token)]
;;     (update-password conn {:user user :password password})
;;     (mark-token-as-used conn token)
;;     nil))

;; (defn- create-recovery-token
;;   "Creates a new recovery token for specified user and return it."
;;   [conn userid]
;;   (let [token (token/random)
;;         sqlv (sql/create-recovery-token {:user userid
;;                                          :token token})]
;;     (db/execute conn sqlv)
;;     token))

;; (defn- retrieve-user-for-password-recovery
;;   [conn username]
;;   (let [user (find-user-by-username-or-email conn username)]
;;     (when-not user
;;       (ex/raise :type :validation :code ::user-does-not-exists))
;;     user))

;; (defn- request-password-recovery
;;   "Creates a new recovery password token and sends it via email
;;   to the correspondig to the given username or email address."
;;   [conn username]
;;   (let [user (retrieve-user-for-password-recovery conn username)
;;         token (create-recovery-token conn (:id user))]
;;     (emails/send! {:email/name :users/password-recovery
;;                    :email/to (:email user)
;;                    :name (:fullname user)
;;                    :token token})
;;     token))

;; (defmethod core/query :validate-profile-password-recovery-token
;;   [{:keys [token]}]
;;   (s/assert ::us/token token)
;;   (with-open [conn (db/connection)]
;;     (recovery-token-exists? conn token)))

;; (defmethod core/novelty :request-profile-password-recovery
;;   [{:keys [username]}]
;;   (s/assert ::us/username username)
;;   (with-open [conn (db/connection)]
;;     (db/atomic conn
;;       (request-password-recovery conn username))))

;; (s/def ::recover-password
;;   (s/keys :req-un [::us/token ::us/password]))

;; (defmethod core/novelty :recover-profile-password
;;   [params]
;;   (s/assert ::recover-password params)
;;   (with-open [conn (db/connection)]
;;     (db/apply-atomic conn recover-password params)))

;; --- Query Helpers

;; (defn find-full-user-by-id
;;   "Find user by its id. This function is for internal
;;   use only because it returns a lot of sensitive information.
;;   If no user is found, `nil` is returned."
;;   [conn id]
;;   (let [sqlv (sql/get-profile {:id id})]
;;     (some-> (db/fetch-one conn sqlv)
;;             (data/normalize-attrs))))

;; (defn find-user-by-id
;;   "Find user by its id. If no user is found, `nil` is returned."
;;   [conn id]
;;   (let [sqlv (sql/get-profile {:id id})]
;;     (some-> (db/fetch-one conn sqlv)
;;             (data/normalize-attrs)
;;             (trim-user-attrs)
;;             (dissoc :password))))

;; (defn find-user-by-username-or-email
;;   "Finds a user in the database by username and email. If no
;;   user is found, `nil` is returned."
;;   [conn username]
;;   (let [sqlv (sql/get-profile-by-username {:username username})]
;;     (some-> (db/fetch-one conn sqlv)
;;             (trim-user-attrs))))

