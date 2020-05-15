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
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [promesa.core :as p]
   [promesa.exec :as px]
   [sodi.prng]
   [sodi.pwhash]
   [sodi.util]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.emails :as emails]
   [uxbox.images :as images]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.images :as imgs]
   [uxbox.services.mutations.projects :as mt.projects]
   [uxbox.services.mutations.teams :as mt.teams]
   [uxbox.services.queries.profile :as profile]
   [uxbox.tasks :as tasks]
   [uxbox.util.blob :as blob]
   [uxbox.util.storage :as ust]
   [uxbox.util.time :as dt]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
(s/def ::lang ::us/string)
(s/def ::path ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/string)
(s/def ::old-password ::us/string)
(s/def ::theme ::us/string)


;; --- Mutation: Login

(declare retrieve-profile-by-email)

(s/def ::email ::us/email)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::email ::password]
          :opt-un [::scope]))

(sm/defmutation ::login
  [{:keys [email password scope] :as params}]
  (letfn [(check-password [profile password]
            (let [result (sodi.pwhash/verify password (:password profile))]
              (:valid result)))

          (check-profile [profile]
            (when-not profile
              (ex/raise :type :validation
                        :code ::wrong-credentials))
            (when-not (check-password profile password)
              (ex/raise :type :validation
                        :code ::wrong-credentials))
            profile)]
    (db/with-atomic [conn db/pool]
      (let [prof (-> (retrieve-profile-by-email conn email)
                     (check-profile)
                     (profile/strip-private-attrs))
            addt (profile/retrieve-additional-data conn (:id prof))]
        (merge prof addt)))))

(defn- retrieve-profile-by-email
  [conn email]
  (db/get-by-params conn :profile {:email email} {:for-update true}))


;; --- Mutation: Update Profile (own)

(def ^:private sql:update-profile
  "update profile
      set fullname = $2,
          lang = $3,
          theme = $4
    where id = $1
      and deleted_at is null
   returning *")

(defn- update-profile
  [conn {:keys [id fullname lang theme] :as params}]
  (db/update! conn :profile
              {:fullname fullname
               :lang lang
               :theme theme}
              {:id id}))

(s/def ::update-profile
  (s/keys :req-un [::id ::fullname ::lang ::theme]))

(sm/defmutation ::update-profile
  [params]
  (db/with-atomic [conn db/pool]
    (update-profile conn params)
    nil))


;; --- Mutation: Update Password

(defn- validate-password!
  [conn {:keys [profile-id old-password] :as params}]
  (let [profile (profile/retrieve-profile conn profile-id)
        result  (sodi.pwhash/verify old-password (:password profile))]
    (when-not (:valid result)
      (ex/raise :type :validation
                :code ::old-password-not-match))))

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password ::old-password]))

(sm/defmutation ::update-profile-password
  [{:keys [password profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (validate-password! conn params)
    (db/update! conn :profile
                {:password (sodi.pwhash/derive password)}
                {:id profile-id})
    nil))



;; --- Mutation: Update Photo

(declare upload-photo)
(declare update-profile-photo)

(s/def ::file ::imgs/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sm/defmutation ::update-profile-photo
  [{:keys [profile-id file] :as params}]
  (db/with-atomic [conn db/pool]
    (let [profile (profile/retrieve-profile conn profile-id)
          photo   (upload-photo conn params)]

      ;; Schedule deletion of old photo
      (when (and (string? (:photo profile))
                 (not (str/blank? (:photo profile))))
        (tasks/submit! conn {:name "remove-media"
                             :props {:path (:photo profile)}}))
      ;; Save new photo
      (update-profile-photo conn profile-id photo))))

(defn- upload-photo
  [conn {:keys [file profile-id]}]
  (when-not (imgs/valid-image-types? (:content-type file))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))
  (let [thumb-opts {:width 256
                    :height 256
                    :quality 75
                    :format "webp"}
        prefix (-> (sodi.prng/random-bytes 8)
                   (sodi.util/bytes->b64s))
        name   (str prefix ".webp")
        path   (fs/path (:tempfile file))
        photo  (images/generate-thumbnail2 path thumb-opts)]
    (ust/save! media/media-storage name photo)))

(defn- update-profile-photo
  [conn profile-id path]
  (let [sql "update profile set photo=$1
              where id=$2
                and deleted_at is null
             returning id"]
    (db/update! conn :profile
                {:photo (str path)}
                {:id profile-id})
    nil))


;; --- Mutation: Register Profile

(declare check-profile-existence!)
(declare register-profile)

(s/def ::register-profile
  (s/keys :req-un [::email ::password ::fullname]))

(defn email-domain-in-whitelist?
  "Returns true if email's domain is in the given whitelist or if given
  whitelist is an empty string."
  [whitelist email]
  (if (str/blank? whitelist)
    true
    (let [domains (str/split whitelist #",\s*")
          email-domain (second (str/split email #"@"))]
      (contains? (set domains) email-domain))))

(sm/defmutation ::register-profile
  [params]
  (when-not (:registration-enabled cfg/config)
    (ex/raise :type :restriction
              :code :registration-disabled))
  (when-not (email-domain-in-whitelist? (:registration-domain-whitelist cfg/config)
                                        (:email params))
    (ex/raise :type :validation
              :code ::email-domain-is-not-allowed))
  (db/with-atomic [conn db/pool]
    (check-profile-existence! conn params)
    (let [profile (register-profile conn params)]
      ;; TODO: send a correct link for email verification
      (let [data {:to (:email params)
                  :name (:fullname params)}]
        (emails/send! conn emails/register data)
        profile))))

(def ^:private sql:insert-profile
  "insert into profile (id, fullname, email, password, photo, is_demo)
   values ($1, $2, $3, $4, '', $5) returning *")

(def ^:private sql:insert-email
  "insert into profile_email (profile_id, email, is_main)
   values ($1, $2, true)")

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = $1
                     and deleted_at is null) as val")

(defn- check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code ::email-already-exists))
    params))

(defn- create-profile
  "Create the profile entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [id fullname email password demo?] :as params}]
  (let [id (or id (uuid/next))
        demo? (if (boolean? demo?) demo? false)
        password (sodi.pwhash/derive password)]
    (db/insert! conn :profile
                {:id id
                 :fullname fullname
                 :email email
                 :photo ""
                 :password password
                 :is-demo demo?})))

(defn- create-profile-email
  [conn {:keys [id email] :as profile}]
  (db/insert! conn :profile-email
              {:profile-id id
               :email email
               :is-main true}))

(defn register-profile
  [conn params]
  (let [prof (create-profile conn params)
        _    (create-profile-email conn prof)

        team (mt.teams/create-team conn {:profile-id (:id prof)
                                         :name "Default"
                                         :default? true})
        _    (mt.teams/create-team-profile conn {:team-id (:id team)
                                                 :profile-id (:id prof)})

        proj (mt.projects/create-project conn {:profile-id (:id prof)
                                               :team-id (:id team)
                                               :name "Drafts"
                                               :default? true})
        _    (mt.projects/create-project-profile conn {:project-id (:id proj)
                                                       :profile-id (:id prof)})
        ]
    (merge (profile/strip-private-attrs prof)
           {:default-team (:id team)
            :default-project (:id proj)})))

;; --- Mutation: Request Profile Recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(def sql:insert-recovery-token
  "insert into password_recovery_token (profile_id, token) values ($1, $2)")

(sm/defmutation ::request-profile-recovery
  [{:keys [email] :as params}]
  (letfn [(create-recovery-token [conn {:keys [id] :as profile}]
            (let [token (-> (sodi.prng/random-bytes 32)
                            (sodi.util/bytes->b64s))
                  sql sql:insert-recovery-token]
              (db/insert! conn :password-recovery-token
                          {:profile-id id
                           :token token})
              (assoc profile :token token)))
          (send-email-notification [conn profile]
            (emails/send! conn emails/password-recovery
                          {:to (:email profile)
                           :token (:token profile)
                           :name (:fullname profile)})
            nil)]
    (db/with-atomic [conn db/pool]
      (let [profile (->> (retrieve-profile-by-email conn email)
                         (create-recovery-token conn))]
        (send-email-notification conn profile)))))


;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(def sql:remove-recovery-token
  "delete from password_recovery_token where profile_id=$1 and token=$2")

(sm/defmutation ::recover-profile
  [{:keys [token password]}]
  (letfn [(validate-token [conn token]
            (let [sql "delete from password_recovery_token
                        where token=$1 returning *"
                  sql "select * from password_recovery_token
                        where token=$1"]
              (-> {:token token}
                  (db/get-by-params conn :password-recovery-token)
                  (:profile-id))))
          (update-password [conn profile-id]
            (let [sql "update profile set password=$2 where id=$1"
                  pwd (sodi.pwhash/derive password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})
              nil))]
    (db/with-atomic [conn db/pool]
      (-> (validate-token conn token)
          (update-password conn)))))


;; --- Mutation: Delete Profile

(declare check-teams-ownership!)
(declare mark-profile-as-deleted!)

(s/def ::delete-profile
  (s/keys :req-un [::profile-id]))

(sm/defmutation ::delete-profile
  [{:keys [profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-teams-ownership! conn profile-id)

    ;; Schedule a complete deletion of profile
    (tasks/submit! conn {:name "delete-profile"
                         :delay (dt/duration {:hours 48})
                         :props {:profile-id profile-id}})

    (db/update! conn :profile
                {:deleted-at (dt/now)}
                {:id profile-id})
    nil))

(def ^:private sql:teams-ownership-check
  "with teams as (
     select tpr.team_id as id
       from team_profile_rel as tpr
      where tpr.profile_id = ?
        and tpr.is_owner is true
   )
   select tpr.team_id,
          count(tpr.profile_id) as num_profiles
     from team_profile_rel as tpr
    where tpr.team_id in (select id from teams)
    group by tpr.team_id
   having count(tpr.profile_id) > 1")

(defn- check-teams-ownership!
  [conn profile-id]
  (let [rows (db/exec! conn [sql:teams-ownership-check profile-id])]
    (when-not (empty? rows)
      (ex/raise :type :validation
                :code :owner-teams-with-people
                :hint "The user need to transfer ownership of owned teams."
                :context {:teams (mapv :team-id rows)}))))
