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
   [uxbox.media :as media]
   [uxbox.media-storage :as mst]
   [uxbox.services.tokens :as tokens]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.media :as media-mutations]
   [uxbox.services.mutations.projects :as projects]
   [uxbox.services.mutations.teams :as teams]
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

;; --- Mutation: Register Profile

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)

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
              :code ::registration-disabled))

  (when-not (email-domain-in-whitelist? (:registration-domain-whitelist cfg/config)
                                        (:email params))
    (ex/raise :type :validation
              :code ::email-domain-is-not-allowed))

  (db/with-atomic [conn db/pool]
    (check-profile-existence! conn params)
    (let [profile (->> (create-profile conn params)
                       (create-profile-relations conn))
          payload {:type :verify-email
                   :profile-id (:id profile)
                   :email (:email profile)}

          token   (tokens/create! conn payload {:valid {:days 30}})]

      (emails/send! conn emails/register
                    {:to (:email profile)
                     :name (:fullname profile)
                     :token token})
      profile)))

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?
                     and deleted_at is null) as val")

(defn- check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [email  (str/lower email)
        result (db/exec-one! conn [sql:profile-existence email])]
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
                 :email (str/lower email)
                 :pending-email (if demo? nil email)
                 :photo ""
                 :password password
                 :is-demo demo?})))

(defn- create-profile-relations
  [conn profile]
  (let [team (teams/create-team conn {:profile-id (:id profile)
                                      :name "Default"
                                      :default? true})
        proj (projects/create-project conn {:profile-id (:id profile)
                                            :team-id (:id team)
                                            :name "Drafts"
                                            :default? true})]
    (teams/create-team-profile conn {:team-id (:id team)
                                     :profile-id (:id profile)})
    (projects/create-project-profile conn {:project-id (:id proj)
                                           :profile-id (:id profile)})

    (merge (profile/strip-private-attrs profile)
           {:default-team-id (:id team)
            :default-project-id (:id proj)})))

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
            (when (= (:password profile) "!")
              (ex/raise :type :validation
                        :code ::account-without-password))
            (let [result (sodi.pwhash/verify password (:password profile))]
              (:valid result)))

          (validate-profile [profile]
            (when-not profile
              (ex/raise :type :validation
                        :code ::wrong-credentials))
            (when-not (check-password profile password)
              (ex/raise :type :validation
                        :code ::wrong-credentials))
            profile)]
    (db/with-atomic [conn db/pool]
      (let [prof (-> (retrieve-profile-by-email conn email)
                     (validate-profile)
                     (profile/strip-private-attrs))
            addt (profile/retrieve-additional-data conn (:id prof))]
        (merge prof addt)))))

(def sql:profile-by-email
  "select * from profile
    where email=? and deleted_at is null
      for update")

(defn- retrieve-profile-by-email
  [conn email]
  (let [email (str/lower email)]
    (db/exec-one! conn [sql:profile-by-email email])))


;; --- Mutation: Register if not exists

(sm/defmutation ::login-or-register
  [{:keys [email fullname] :as params}]
  (letfn [(populate-additional-data [conn profile]
            (let [data (profile/retrieve-additional-data conn (:id profile))]
              (merge profile data)))

          (create-profile [conn {:keys [fullname email]}]
            (db/insert! conn :profile
                        {:id (uuid/next)
                         :fullname fullname
                         :email (str/lower email)
                         :pending-email nil
                         :photo ""
                         :password "!"
                         :is-demo false}))

          (register-profile [conn params]
            (->> (create-profile conn params)
                 (create-profile-relations conn)))]

    (db/with-atomic [conn db/pool]
      (let [profile (retrieve-profile-by-email conn email)
            profile (if profile
                      (populate-additional-data conn profile)
                      (register-profile conn params))]
        (profile/strip-private-attrs profile)))))


;; --- Mutation: Update Profile (own)

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
  (let [profile (profile/retrieve-profile-data conn profile-id)
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

(s/def ::file ::media-mutations/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sm/defmutation ::update-profile-photo
  [{:keys [profile-id file] :as params}]
  (when-not (media-mutations/valid-media-object-types? (:content-type file))
    (ex/raise :type :validation
              :code :media-type-not-allowed
              :hint "Seems like you are uploading an invalid media object"))

  (db/with-atomic [conn db/pool]
    (let [profile (profile/retrieve-profile conn profile-id)
          _       (media/run {:cmd :info :input {:path (:tempfile file)
                                                  :mtype (:content-type file)}})
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
  (let [prefix (-> (sodi.prng/random-bytes 8)
                   (sodi.util/bytes->b64s))
        thumb  (media/run
                 {:cmd :profile-thumbnail
                  :format :jpeg
                  :quality 85
                  :width 256
                  :height 256
                  :input  {:path (fs/path (:tempfile file))
                           :mtype (:content-type file)}})
        name   (str prefix (media/format->extension (:format thumb)))]
    (ust/save! mst/media-storage name (:data thumb))))

(defn- update-profile-photo
  [conn profile-id path]
  (db/update! conn :profile
              {:photo (str path)}
              {:id profile-id})
  nil)

;; --- Mutation: Request Email Change

(declare select-profile-for-update)

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sm/defmutation ::request-email-change
  [{:keys [profile-id email] :as params}]
  (db/with-atomic [conn db/pool]
    (let [email   (str/lower email)
          profile (select-profile-for-update conn profile-id)
          payload {:type :change-email
                   :profile-id profile-id
                   :email email}

          token   (tokens/create! conn payload)]

      (when (not= email (:email profile))
        (check-profile-existence! conn params))

      (db/update! conn :profile
                  {:pending-email email}
                  {:id profile-id})

      (emails/send! conn emails/change-email
                    {:to (:email profile)
                     :name (:fullname profile)
                     :pending-email email
                     :token token})
      nil)))

(defn- select-profile-for-update
  [conn id]
  (db/get-by-id conn :profile id {:for-update true}))


;; --- Mutation: Verify Profile Token

;; Generic mutation for perform token based verification for auth
;; domain.

(s/def ::verify-profile-token
  (s/keys :req-un [::token]))

(sm/defmutation ::verify-profile-token
  [{:keys [token] :as params}]
  (letfn [(handle-email-change [conn tdata]
            (let [profile (select-profile-for-update conn (:profile-id tdata))]
              (when (not= (:email tdata)
                          (:pending-email profile))
                (ex/raise :type :validation
                          :code ::email-does-not-match))
              (check-profile-existence! conn {:email (:pending-email profile)})
              (db/update! conn :profile
                            {:pending-email nil
                             :email (:pending-email profile)}
                            {:id (:id profile)})

              tdata))

          (handle-email-verify [conn tdata]
            (let [profile (select-profile-for-update conn (:profile-id tdata))]
              (when (or (not= (:email profile)
                              (:pending-email profile))
                        (not= (:email profile)
                              (:email tdata)))
                (ex/raise :type :validation
                          :code ::tokens/invalid-token))

              (db/update! conn :profile
                          {:pending-email nil}
                          {:id (:id profile)})
              tdata))]

    (db/with-atomic [conn db/pool]
      (let [tdata (tokens/retrieve conn token {:delete true})]
        (tokens/delete! conn token)
        (case (:type tdata)
          :change-email   (handle-email-change conn tdata)
          :verify-email   (handle-email-verify conn tdata)
          :authentication tdata
          (ex/raise :type :validation
                    :code ::tokens/invalid-token))))))

;; --- Mutation: Cancel Email Change

(s/def ::cancel-email-change
  (s/keys :req-un [::profile-id]))

(sm/defmutation ::cancel-email-change
  [{:keys [profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [profile (select-profile-for-update conn profile-id)]
      (when (= (:email profile)
               (:pending-email profile))
        (ex/raise :type :validation
                  :code ::unexpected-request))

      (db/update! conn :profile {:pending-email nil} {:id profile-id})
      nil)))

;; --- Mutation: Request Profile Recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(sm/defmutation ::request-profile-recovery
  [{:keys [email] :as params}]
  (letfn [(create-recovery-token [conn {:keys [id] :as profile}]
            (let [payload {:type :password-recovery-token
                           :profile-id id}
                  token   (tokens/create! conn payload)]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (emails/send! conn emails/password-recovery
                          {:to (:email profile)
                           :token (:token profile)
                           :name (:fullname profile)}))]

    (db/with-atomic [conn db/pool]
      (some->> email
               (retrieve-profile-by-email conn)
               (create-recovery-token conn)
               (send-email-notification conn))
      nil)))


;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sm/defmutation ::recover-profile
  [{:keys [token password]}]
  (letfn [(validate-token [conn token]
            (let [tpayload (tokens/retrieve conn token)]
              (when (not= (:type tpayload) :password-recovery-token)
                (ex/raise :type :validation
                          :code ::tokens/invalid-token))
              (:profile-id tpayload)))

          (update-password [conn profile-id]
            (let [pwd (sodi.pwhash/derive password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})))

          (delete-token [conn token]
            (db/delete! conn :generic-token {:token token}))]


    (db/with-atomic [conn db/pool]
      (->> (validate-token conn token)
           (update-password conn))
      (delete-token conn token)
      nil)))


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
                :code ::owner-teams-with-people
                :hint "The user need to transfer ownership of owned teams."
                :context {:teams (mapv :team-id rows)}))))
