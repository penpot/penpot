;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.mutations.profile
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.http.session :as session]
   [app.media :as media]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.mutations.teams :as teams]
   [app.rpc.mutations.verify-token :refer [process-token]]
   [app.rpc.queries.profile :as profile]
   [app.util.services :as sv]
   [app.tasks :as tasks]
   [app.util.time :as dt]
   [buddy.hashers :as hashers]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/not-empty-string)
(s/def ::lang ::us/not-empty-string)
(s/def ::path ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)
(s/def ::theme ::us/string)

;; --- Mutation: Register Profile

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)
(declare email-domain-in-whitelist?)

(s/def ::token ::us/not-empty-string)
(s/def ::register-profile
  (s/keys :req-un [::email ::password ::fullname]
          :opt-un [::token]))

(sv/defmethod ::register-profile {:auth false}
  [{:keys [pool tokens session] :as cfg} {:keys [token] :as params}]
  (when-not (:registration-enabled cfg/config)
    (ex/raise :type :restriction
              :code :registration-disabled))

  (when-not (email-domain-in-whitelist? (:registration-domain-whitelist cfg/config)
                                        (:email params))
    (ex/raise :type :validation
              :code :email-domain-is-not-allowed))

  (db/with-atomic [conn pool]
    (check-profile-existence! conn params)
    (let [profile (->> (create-profile conn params)
                       (create-profile-relations conn))]

      (if token
        ;; If token comes in params, this is because the user comes
        ;; from team-invitation process; in this case we revalidate
        ;; the token and process the token claims again with the new
        ;; profile data.
        (let [claims (tokens :verify {:token token :iss :team-invitation})
              claims (assoc claims :member-id  (:id profile))
              params (assoc params :profile-id (:id profile))]
          (process-token conn params claims)

          ;; Automatically mark the created profile as active because
          ;; we already have the verification of email with the
          ;; team-invitation token.
          (db/update! conn :profile
                      {:is-active true}
                      {:id (:id profile)})

          ;; Return profile data and create http session for
          ;; automatically login the profile.
          (with-meta (assoc profile
                            :is-active true
                            :claims claims)
            {:transform-response
             (fn [request response]
               (let [uagent (get-in request [:headers "user-agent"])
                     id     (session/create! session {:profile-id (:id profile)
                                                      :user-agent uagent})]
                 (assoc response
                        :cookies (session/cookies session {:value id}))))}))

        ;; If no token is provided, send a verification email
        (let [token (tokens :generate
                            {:iss :verify-email
                             :exp (dt/in-future "48h")
                             :profile-id (:id profile)
                             :email (:email profile)})]

          (emails/send! conn emails/register
                        {:to (:email profile)
                         :name (:fullname profile)
                         :token token})

          profile)))))


(defn email-domain-in-whitelist?
  "Returns true if email's domain is in the given whitelist or if given
  whitelist is an empty string."
  [whitelist email]
  (if (str/blank? whitelist)
    true
    (let [domains (str/split whitelist #",\s*")
          email-domain (second (str/split email #"@"))]
      (contains? (set domains) email-domain))))

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?
                     and deleted_at is null) as val")

(defn check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [email  (str/lower email)
        result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code :email-already-exists))
    params))

(defn- derive-password
  [password]
  (hashers/derive password
                  {:alg :argon2id
                   :memory 16384
                   :iterations 20
                   :parallelism 2}))

(defn- verify-password
  [attempt password]
  (try
    (hashers/verify attempt password)
    (catch Exception e
      (log/warnf e "Error on verify password (only informative, nothing affected to user).")
      {:update false
       :valid false})))

(defn- create-profile
  "Create the profile entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [id fullname email password demo?] :as params}]
  (let [id       (or id (uuid/next))
        demo?    (if (boolean? demo?) demo? false)
        active?  (if demo? true false)
        password (derive-password password)]
    (db/insert! conn :profile
                {:id id
                 :fullname fullname
                 :email (str/lower email)
                 :photo ""
                 :password password
                 :is-active active?
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

(s/def ::email ::us/email)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::email ::password]
          :opt-un [::scope]))

(sv/defmethod ::login {:auth false}
  [{:keys [pool] :as cfg} {:keys [email password scope] :as params}]
  (letfn [(check-password [profile password]
            (when (= (:password profile) "!")
              (ex/raise :type :validation
                        :code :account-without-password))
            (:valid (verify-password password (:password profile))))

          (validate-profile [profile]
            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not (check-password profile password)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            profile)]

    (db/with-atomic [conn pool]
      (let [prof (-> (profile/retrieve-profile-data-by-email conn email)
                     (validate-profile)
                     (profile/strip-private-attrs))
            addt (profile/retrieve-additional-data conn (:id prof))]
        (merge prof addt)))))


;; --- Mutation: Register if not exists

(sv/defmethod ::login-or-register
  [{:keys [pool] :as cfg} {:keys [email fullname] :as params}]
  (letfn [(populate-additional-data [conn profile]
            (let [data (profile/retrieve-additional-data conn (:id profile))]
              (merge profile data)))

          (create-profile [conn {:keys [fullname email]}]
            (db/insert! conn :profile
                        {:id (uuid/next)
                         :fullname fullname
                         :email (str/lower email)
                         :is-active true
                         :photo ""
                         :password "!"
                         :is-demo false}))

          (register-profile [conn params]
            (->> (create-profile conn params)
                 (create-profile-relations conn)))]

    (db/with-atomic [conn pool]
      (let [profile (profile/retrieve-profile-data-by-email conn email)
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

(sv/defmethod ::update-profile
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (update-profile conn params)
    nil))


;; --- Mutation: Update Password

(defn- validate-password!
  [conn {:keys [profile-id old-password] :as params}]
  (let [profile (db/get-by-id conn :profile profile-id)]
    (when-not (:valid (verify-password old-password (:password profile)))
      (ex/raise :type :validation
                :code :old-password-not-match))))

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password ::old-password]))

(sv/defmethod ::update-profile-password
  [{:keys [pool] :as cfg} {:keys [password profile-id] :as params}]
  (db/with-atomic [conn pool]
    (validate-password! conn params)
    (db/update! conn :profile
                {:password (derive-password password)}
                {:id profile-id})
    nil))


;; --- Mutation: Update Photo

(declare update-profile-photo)

(s/def ::file ::media/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sv/defmethod ::update-profile-photo
  [{:keys [pool] :as cfg} {:keys [profile-id file] :as params}]
  (media/validate-media-type (:content-type file))
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id)
          _       (media/run {:cmd :info :input {:path (:tempfile file)
                                                 :mtype (:content-type file)}})
          photo   (teams/upload-photo cfg params)]

      ;; Schedule deletion of old photo
      (when (and (string? (:photo profile))
                 (not (str/blank? (:photo profile))))
        (tasks/submit! conn {:name "remove-media"
                             :props {:path (:photo profile)}}))
      ;; Save new photo
      (update-profile-photo conn profile-id photo))))

(defn- update-profile-photo
  [conn profile-id path]
  (db/update! conn :profile
              {:photo (str path)}
              {:id profile-id})
  nil)

;; --- Mutation: Request Email Change

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sv/defmethod ::request-email-change
  [{:keys [pool tokens] :as cfg} {:keys [profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [email   (str/lower email)
          profile (db/get-by-id conn :profile profile-id)
          token   (tokens :generate
                          {:iss :change-email
                           :exp (dt/in-future "15m")
                           :profile-id profile-id
                           :email email})]

      (when (not= email (:email profile))
        (check-profile-existence! conn params))

      (emails/send! conn emails/change-email
                    {:to (:email profile)
                     :name (:fullname profile)
                     :pending-email email
                     :token token})
      nil)))

(defn select-profile-for-update
  [conn id]
  (db/get-by-id conn :profile id {:for-update true}))

;; --- Mutation: Request Profile Recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(sv/defmethod ::request-profile-recovery {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [email] :as params}]
  (letfn [(create-recovery-token [{:keys [id] :as profile}]
            (let [token (tokens :generate
                                {:iss :password-recovery
                                 :exp (dt/in-future "15m")
                                 :profile-id id})]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (emails/send! conn emails/password-recovery
                          {:to (:email profile)
                           :token (:token profile)
                           :name (:fullname profile)}))]

    (db/with-atomic [conn pool]
      (some->> email
               (profile/retrieve-profile-data-by-email conn)
               (create-recovery-token)
               (send-email-notification conn))
      nil)))


;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sv/defmethod ::recover-profile {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [token password]}]
  (letfn [(validate-token [token]
            (let [tdata (tokens :verify {:token token :iss :password-recovery})]
              (:profile-id tdata)))

          (update-password [conn profile-id]
            (let [pwd (derive-password password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})))]

    (db/with-atomic [conn pool]
      (->> (validate-token token)
           (update-password conn))
      nil)))

;; --- Mutation: Update Profile Props

(s/def ::props map?)
(s/def ::update-profile-props
  (s/keys :req-un [::profile-id ::props]))

(sv/defmethod ::update-profile-props
  [{:keys [pool] :as cfg} {:keys [profile-id props]}]
  (db/with-atomic [conn pool]
    (let [profile (profile/retrieve-profile-data conn profile-id)
          props   (reduce-kv (fn [props k v]
                               (if (nil? v)
                                 (dissoc props k)
                                 (assoc props k v)))
                             (:props profile)
                             props)]
      (db/update! conn :profile
                  {:props (db/tjson props)}
                  {:id profile-id})
      nil)))


;; --- Mutation: Delete Profile

(declare check-teams-ownership!)
(declare mark-profile-as-deleted!)

(s/def ::delete-profile
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::delete-profile
  [{:keys [pool session] :as cfg} {:keys [profile-id] :as params}]
  (db/with-atomic [conn pool]
    (check-teams-ownership! conn profile-id)

    ;; Schedule a complete deletion of profile
    (tasks/submit! conn {:name "delete-profile"
                         :delay (dt/duration {:hours 48})
                         :props {:profile-id profile-id}})

    (db/update! conn :profile
                {:deleted-at (dt/now)}
                {:id profile-id})

    (with-meta {}
      {:transform-response
       (fn [request response]
         (session/delete! session request)
         (assoc response
                :cookies (session/cookies session {:value "" :max-age -1})))})))

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
