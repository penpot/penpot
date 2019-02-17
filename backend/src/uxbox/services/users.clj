;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.users
  (:require [clojure.spec :as s]
            [mount.core :as mount :refer (defstate)]
            [suricatta.core :as sc]
            [buddy.hashers :as hashers]
            [buddy.sign.jwe :as jwe]
            [uxbox.sql :as sql]
            [uxbox.db :as db]
            [uxbox.util.spec :as us]
            [uxbox.emails :as emails]
            [uxbox.services.core :as core]
            [uxbox.util.transit :as t]
            [uxbox.util.exceptions :as ex]
            [uxbox.util.data :as data]
            [uxbox.util.blob :as blob]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.token :as token]))

(declare decode-user-data)
(declare trim-user-attrs)
(declare find-user-by-id)
(declare find-full-user-by-id)
(declare find-user-by-username-or-email)

(s/def ::user uuid?)
(s/def ::fullname string?)
(s/def ::metadata any?)
(s/def ::old-password string?)
(s/def ::path string?)

;; --- Retrieve User Profile (own)

(defmethod core/query :retrieve-profile
  [{:keys [user] :as params}]
  (s/assert ::user user)
  (with-open [conn (db/connection)]
    (some-> (find-user-by-id conn (:user params))
            (decode-user-data))))

;; --- Update User Profile (own)

(defn- check-profile-existence!
  [conn {:keys [id username email]}]
  (let [sqlv1 (sql/user-with-email-exists? {:id id :email email})
        sqlv2 (sql/user-with-username-exists? {:id id :username username})]
    (when (:val (sc/fetch-one conn sqlv1))
      (ex/raise :type :validation
                :code ::email-already-exists))
    (when (:val (sc/fetch-one conn sqlv2))
      (ex/raise ::username-already-exists))))

(defn- update-profile
  [conn {:keys [id username email fullname metadata] :as params}]
  (check-profile-existence! conn params)
  (let [metadata (-> metadata t/encode blob/encode)
        sqlv (sql/update-profile {:username username
                                  :fullname fullname
                                  :metadata metadata
                                  :email email
                                  :id id})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs)
            (trim-user-attrs)
            (decode-user-data)
            (dissoc :password))))

(s/def ::update-profile
  (s/keys :req-un [::us/id ::us/username ::us/email ::fullname ::metadata]))

(defmethod core/novelty :update-profile
  [params]
  (s/assert ::update-profile params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn update-profile params)))

;; --- Update Password

(defn update-password
  [conn {:keys [user password]}]
  (let [password (hashers/encrypt password)
        sqlv (sql/update-profile-password {:id user :password password})]
    (pos? (sc/execute conn sqlv))))

(defn- validate-old-password
  [conn {:keys [user old-password] :as params}]
  (let [user (find-full-user-by-id conn user)]
    (when-not (hashers/check old-password (:password user))
      (ex/raise :type :validation
                :code ::old-password-not-match))
    params))

(s/def ::update-password
  (s/keys :req-un [::user ::us/password ::old-password]))

(defmethod core/novelty :update-profile-password
  [params]
  (s/assert ::update-password params)
  (with-open [conn (db/connection)]
    (->> params
         (validate-old-password conn)
         (update-password conn))))

;; --- Update Photo

(defn update-photo
  [conn {:keys [user path]}]
  (let [sqlv (sql/update-profile-photo {:id user :photo path})]
    (pos? (sc/execute conn sqlv))))

(s/def ::update-photo
  (s/keys :req-un [::user ::path]))

(defmethod core/novelty :update-profile-photo
  [params]
  (s/assert ::update-photo params)
  (with-open [conn (db/connection)]
    (update-photo conn params)))

;; --- Create User

(s/def ::create-user
  (s/keys :req-un [::metadata ::fullname ::us/email ::us/password]
          :opt-un [::us/id]))

(defn create-user
  [conn {:keys [id username password email fullname metadata] :as data}]
  (s/assert ::create-user data)
  (let [id (or id (uuid/random))
        metadata (-> metadata t/encode blob/encode)
        password (hashers/encrypt password)
        sqlv (sql/create-profile {:id id
                                  :fullname fullname
                                  :username username
                                  :email email
                                  :password password
                                  :metadata metadata})]
    (->> (sc/fetch-one conn sqlv)
         (data/normalize-attrs)
         (trim-user-attrs)
         (decode-user-data))))

;; --- Register User

(defn- check-user-registred!
  "Check if the user identified by username or by email
  is already registred in the platform."
  [conn {:keys [username email]}]
  (let [sqlv1 (sql/user-with-email-exists? {:email email})
        sqlv2 (sql/user-with-username-exists? {:username username})]
    (when (:val (sc/fetch-one conn sqlv1))
      (ex/raise :type :validation
                :code ::email-already-exists))
    (when (:val (sc/fetch-one conn sqlv2))
      (ex/raise :type :validation
                :code ::username-already-exists))))

(defn- register-user
  "Create the user entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [username fullname email password] :as params}]
  (check-user-registred! conn params)
  (let [metadata (-> nil t/encode blob/encode)
        password (hashers/encrypt password)
        sqlv (sql/create-profile {:id (uuid/random)
                                  :fullname fullname
                                  :username username
                                  :email email
                                  :password password
                                  :metadata metadata})]
    (sc/execute conn sqlv)
    (emails/send! {:email/name :users/register
                   :email/to (:email params)
                   :email/priority :high
                   :name (:fullname params)})
    nil))

(s/def ::register
  (s/keys :req-un [::us/username ::us/email ::us/password ::fullname]))

(defmethod core/novelty :register-profile
  [params]
  (s/assert ::register params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn register-user params)))

;; --- Password Recover

(defn- recovery-token-exists?
  "Checks if the token exists in the system. Just
  return `true` or `false`."
  [conn token]
  (let [sqlv (sql/recovery-token-exists? {:token token})
        result (sc/fetch-one conn sqlv)]
    (:token_exists result)))

(defn- retrieve-user-for-recovery-token
  "Retrieve a user id (uuid) for the given token. If
  no user is found, an exception is raised."
  [conn token]
  (let [sqlv (sql/get-recovery-token {:token token})
        data (sc/fetch-one conn sqlv)]
    (or (:user data)
        (ex/raise :type :validation
                  :code ::invalid-token))))

(defn- mark-token-as-used
  [conn token]
  (let [sqlv (sql/mark-recovery-token-used {:token token})]
    (pos? (sc/execute conn sqlv))))

(defn- recover-password
  "Given a token and password, resets the password
  to corresponding user or raise an exception."
  [conn {:keys [token password]}]
  (let [user (retrieve-user-for-recovery-token conn token)]
    (update-password conn {:user user :password password})
    (mark-token-as-used conn token)
    nil))

(defn- create-recovery-token
  "Creates a new recovery token for specified user and return it."
  [conn userid]
  (let [token (token/random)
        sqlv (sql/create-recovery-token {:user userid
                                         :token token})]
    (sc/execute conn sqlv)
    token))

(defn- retrieve-user-for-password-recovery
  [conn username]
  (let [user (find-user-by-username-or-email conn username)]
    (when-not user
      (ex/raise :type :validation :code ::user-does-not-exists))
    user))

(defn- request-password-recovery
  "Creates a new recovery password token and sends it via email
  to the correspondig to the given username or email address."
  [conn username]
  (let [user (retrieve-user-for-password-recovery conn username)
        token (create-recovery-token conn (:id user))]
    (emails/send! {:email/name :users/password-recovery
                   :email/to (:email user)
                   :name (:fullname user)
                   :token token})
    token))

(defmethod core/query :validate-profile-password-recovery-token
  [{:keys [token]}]
  (s/assert ::us/token token)
  (with-open [conn (db/connection)]
    (recovery-token-exists? conn token)))

(defmethod core/novelty :request-profile-password-recovery
  [{:keys [username]}]
  (s/assert ::us/username username)
  (with-open [conn (db/connection)]
    (sc/atomic conn
      (request-password-recovery conn username))))

(s/def ::recover-password
  (s/keys :req-un [::us/token ::us/password]))

(defmethod core/novelty :recover-profile-password
  [params]
  (s/assert ::recover-password params)
  (with-open [conn (db/connection)]
    (sc/apply-atomic conn recover-password params)))

;; --- Query Helpers

(defn find-full-user-by-id
  "Find user by its id. This function is for internal
  use only because it returns a lot of sensitive information.
  If no user is found, `nil` is returned."
  [conn id]
  (let [sqlv (sql/get-profile {:id id})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs))))

(defn find-user-by-id
  "Find user by its id. If no user is found, `nil` is returned."
  [conn id]
  (let [sqlv (sql/get-profile {:id id})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs)
            (trim-user-attrs)
            (dissoc :password))))

(defn find-user-by-username-or-email
  "Finds a user in the database by username and email. If no
  user is found, `nil` is returned."
  [conn username]
  (let [sqlv (sql/get-profile-by-username {:username username})]
    (some-> (sc/fetch-one conn sqlv)
            (data/normalize-attrs)
            (trim-user-attrs))))

;; --- Attrs Helpers

(defn- decode-user-data
  [{:keys [metadata] :as result}]
  (merge result (when metadata
                  {:metadata (-> metadata blob/decode t/decode)})))

(defn trim-user-attrs
  "Only selects a publicy visible user attrs."
  [user]
  (select-keys user [:id :username :fullname
                     :password :metadata :email
                     :created-at :photo]))
