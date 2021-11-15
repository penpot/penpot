;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.profile
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.http.oauth :refer [extract-utm-props]]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.metrics :as mtx]
   [app.rpc.mutations.teams :as teams]
   [app.rpc.queries.profile :as profile]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [buddy.hashers :as hashers]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/not-empty-string)
(s/def ::lang (s/nilable ::us/not-empty-string))
(s/def ::path ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)
(s/def ::theme ::us/string)
(s/def ::invitation-token ::us/not-empty-string)

(declare annotate-profile-register)
(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)
(declare register-profile)

(defn email-domain-in-whitelist?
  "Returns true if email's domain is in the given whitelist or if
  given whitelist is an empty string."
  [domains email]
  (if (or (empty? domains)
          (nil? domains))
    true
    (let [[_ candidate] (-> (str/lower email)
                            (str/split #"@" 2))]
      (contains? domains candidate))))

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

(defn derive-password
  [password]
  (hashers/derive password
                  {:alg :argon2id
                   :memory 16384
                   :iterations 20
                   :parallelism 2}))

(defn verify-password
  [attempt password]
  (try
    (hashers/verify attempt password)
    (catch Exception _e
      {:update false
       :valid false})))

(defn decode-profile-row
  [{:keys [props] :as profile}]
  (cond-> profile
    (db/pgobject? props "jsonb")
    (assoc :props (db/decode-transit-pgobject props))))

;; --- MUTATION: Prepare Register

(s/def ::prepare-register-profile
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(sv/defmethod ::prepare-register-profile {:auth false}
  [{:keys [pool tokens] :as cfg} params]
  (when-not (contains? cf/flags :registration)
    (ex/raise :type :restriction
              :code :registration-disabled))
  (when-let [domains (cf/get :registration-domain-whitelist)]
    (when-not (email-domain-in-whitelist? domains (:email params))
      (ex/raise :type :validation
                :code :email-domain-is-not-allowed)))

  ;; Don't allow proceed in preparing registration if the profile is
  ;; already reported as spammer.
  (when (eml/has-bounce-reports? pool (:email params))
    (ex/raise :type :validation
              :code :email-has-permanent-bounces
              :hint "looks like the email has one or many bounces reported"))

  (check-profile-existence! pool params)

  (let [params (assoc params
                      :backend "penpot"
                      :iss :prepared-register
                      :exp (dt/in-future "48h"))
        token  (tokens :generate params)]
    {:token token}))

;; --- MUTATION: Register Profile

(s/def ::token ::us/not-empty-string)
(s/def ::register-profile
  (s/keys :req-un [::token ::fullname]))

(sv/defmethod ::register-profile {:auth false :rlimit :password}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (-> (assoc cfg :conn conn)
        (register-profile params))))

(defn- annotate-profile-register
  "A helper for properly increase the profile-register metric once the
  transaction is completed."
  [metrics]
  (fn []
    (let [mobj (get-in metrics [:definitions :profile-register])]
      ((::mtx/fn mobj) {:by 1}))))

(defn register-profile
  [{:keys [conn tokens session metrics] :as cfg} {:keys [token] :as params}]
  (let [claims    (tokens :verify {:token token :iss :prepared-register})
        params    (merge params claims)]

    (check-profile-existence! conn params)

    (let [is-active (or (:is-active params)
                        (contains? cf/flags :insecure-register))
          profile   (->> (assoc params :is-active is-active)
                         (create-profile conn)
                         (create-profile-relations conn)
                         (decode-profile-row))]
      (cond
        ;; If invitation token comes in params, this is because the
        ;; user comes from team-invitation process; in this case,
        ;; regenerate token and send back to the user a new invitation
        ;; token (and mark current session as logged).
        (some? (:invitation-token params))
        (let [token (:invitation-token params)
              claims (tokens :verify {:token token :iss :team-invitation})
              claims (assoc claims
                            :member-id  (:id profile)
                            :member-email (:email profile))
              token  (tokens :generate claims)
              resp   {:invitation-token token}]
          (with-meta resp
            {:transform-response ((:create session) (:id profile))
             :before-complete (annotate-profile-register metrics)
             ::audit/props (audit/profile->props profile)
             ::audit/profile-id (:id profile)}))

        ;; If auth backend is different from "penpot" means user is
        ;; registering using third party auth mechanism; in this case
        ;; we need to mark this session as logged.
        (not= "penpot" (:auth-backend profile))
        (with-meta (profile/strip-private-attrs profile)
          {:transform-response ((:create session) (:id profile))
           :before-complete (annotate-profile-register metrics)
           ::audit/props (audit/profile->props profile)
           ::audit/profile-id (:id profile)})

        ;; If the `:enable-insecure-register` flag is set, we proceed
        ;; to sign in the user directly, without email verification.
        (true? is-active)
        (with-meta (profile/strip-private-attrs profile)
          {:transform-response ((:create session) (:id profile))
           :before-complete (annotate-profile-register metrics)
           ::audit/props (audit/profile->props profile)
           ::audit/profile-id (:id profile)})

        ;; In all other cases, send a verification email.
        :else
        (let [vtoken (tokens :generate
                             {:iss :verify-email
                              :exp (dt/in-future "48h")
                              :profile-id (:id profile)
                              :email (:email profile)})
              ptoken (tokens :generate-predefined
                             {:iss :profile-identity
                              :profile-id (:id profile)})]
          (eml/send! {::eml/conn conn
                      ::eml/factory eml/register
                      :public-uri (:public-uri cfg)
                      :to (:email profile)
                      :name (:fullname profile)
                      :token vtoken
                      :extra-data ptoken})

          (with-meta profile
            {:before-complete (annotate-profile-register metrics)
             ::audit/props (audit/profile->props profile)
             ::audit/profile-id (:id profile)}))))))

(defn create-profile
  "Create the profile entry on the database with limited input filling
  all the other fields with defaults."
  [conn params]
  (let [id        (or (:id params) (uuid/next))

        props     (-> (extract-utm-props params)
                      (merge (:props params))
                      (db/tjson))

        password  (if-let [password (:password params)]
                    (derive-password password)
                    "!")

        locale    (:locale params)
        locale    (when (and (string? locale) (not (str/blank? locale)))
                    locale)

        backend   (:backend params "penpot")
        is-demo   (:is-demo params false)
        is-muted  (:is-muted params false)
        is-active (:is-active params false)
        email     (str/lower (:email params))

        params    {:id id
                   :fullname (:fullname params)
                   :email email
                   :auth-backend backend
                   :lang locale
                   :password password
                   :deleted-at (:deleted-at params)
                   :props props
                   :is-active is-active
                   :is-muted is-muted
                   :is-demo is-demo}]
    (try
      (-> (db/insert! conn :profile params)
          (decode-profile-row))
      (catch org.postgresql.util.PSQLException e
        (let [state (.getSQLState e)]
          (if (not= state "23505")
            (throw e)
            (ex/raise :type :validation
                      :code :email-already-exists
                      :cause e)))))))

(defn create-profile-relations
  [conn profile]
  (let [team (teams/create-team conn {:profile-id (:id profile)
                                      :name "Default"
                                      :is-default true})]
    (-> profile
        (profile/strip-private-attrs)
        (assoc :default-team-id (:id team))
        (assoc :default-project-id (:default-project-id team)))))

;; --- MUTATION: Login

(s/def ::email ::us/email)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::email ::password]
          :opt-un [::scope ::invitation-token]))

(sv/defmethod ::login {:auth false :rlimit :password}
  [{:keys [pool session tokens] :as cfg} {:keys [email password] :as params}]
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
      (let [profile (->> (profile/retrieve-profile-data-by-email conn email)
                         (validate-profile)
                         (profile/strip-private-attrs)
                         (profile/populate-additional-data conn)
                         (decode-profile-row))]
        (if-let [token (:invitation-token params)]
          ;; If the request comes with an invitation token, this means
          ;; that user wants to accept it with different user. A very
          ;; strange case but still can happen. In this case, we
          ;; proceed in the same way as in register: regenerate the
          ;; invitation token and return it to the user for proper
          ;; invitation acceptation.
          (let [claims (tokens :verify {:token token :iss :team-invitation})
                claims (assoc claims
                              :member-id  (:id profile)
                              :member-email (:email profile))
                token  (tokens :generate claims)]
            (with-meta {:invitation-token token}
              {:transform-response ((:create session) (:id profile))
               ::audit/props (audit/profile->props profile)
               ::audit/profile-id (:id profile)}))

          (with-meta profile
            {:transform-response ((:create session) (:id profile))
             ::audit/props (audit/profile->props profile)
             ::audit/profile-id (:id profile)}))))))

;; --- MUTATION: Logout

(s/def ::logout
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::logout
  [{:keys [session] :as cfg} _]
  (with-meta {}
    {:transform-response (:delete session)}))

;; --- MUTATION: Update Profile (own)

(defn- update-profile
  [conn {:keys [id fullname lang theme] :as params}]
  (let [profile (db/update! conn :profile
                            {:fullname fullname
                             :lang lang
                             :theme theme}
                            {:id id})]
    (-> profile
        (profile/decode-profile-row)
        (profile/strip-private-attrs))))

(s/def ::update-profile
  (s/keys :req-un [::id ::fullname]
          :opt-un [::lang ::theme]))

(sv/defmethod ::update-profile
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (let [profile (update-profile conn params)]
      (with-meta profile
        {::audit/props (audit/profile->props profile)}))))

;; --- MUTATION: Update Password

(declare validate-password!)
(declare update-profile-password!)

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password ::old-password]))

(sv/defmethod ::update-profile-password {:rlimit :password}
  [{:keys [pool] :as cfg} {:keys [password] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (validate-password! conn params)]
      (update-profile-password! conn (assoc profile :password password))
      nil)))

(defn- validate-password!
  [conn {:keys [profile-id old-password] :as params}]
  (let [profile (db/get-by-id conn :profile profile-id)]
    (when-not (:valid (verify-password old-password (:password profile)))
      (ex/raise :type :validation
                :code :old-password-not-match))
    profile))

(defn update-profile-password!
  [conn {:keys [id password] :as profile}]
  (db/update! conn :profile
              {:password (derive-password password)}
              {:id id}))


;; --- MUTATION: Update Photo

(declare update-profile-photo)

(s/def ::content-type ::media/image-content-type)
(s/def ::file (s/and ::media/upload (s/keys :req-un [::content-type])))

(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sv/defmethod ::update-profile-photo
  [{:keys [pool storage] :as cfg} {:keys [profile-id file] :as params}]
  (db/with-atomic [conn pool]
    (media/validate-media-type (:content-type file) #{"image/jpeg" "image/png" "image/webp"})
    (media/run cfg {:cmd :info :input {:path (:tempfile file)
                                       :mtype (:content-type file)}})

    (let [profile (db/get-by-id conn :profile profile-id)
          storage (media/configure-assets-storage storage conn)
          cfg     (assoc cfg :storage storage)
          photo   (teams/upload-photo cfg params)]

      ;; Schedule deletion of old photo
      (when-let [id (:photo-id profile)]
        (sto/del-object storage id))

      ;; Save new photo
      (update-profile-photo conn profile-id photo))))

(defn- update-profile-photo
  [conn profile-id sobj]
  (db/update! conn :profile
              {:photo-id (:id sobj)}
              {:id profile-id})
  nil)


;; --- MUTATION: Request Email Change

(declare request-email-change)
(declare change-email-immediately)

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sv/defmethod ::request-email-change
  [{:keys [pool] :as cfg} {:keys [profile-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [profile (db/get-by-id conn :profile profile-id)
          cfg     (assoc cfg :conn conn)
          params  (assoc params
                         :profile profile
                         :email (str/lower email))]
      (if (or (cf/get :smtp-enabled)
              (contains? cf/flags :smtp))
        (request-email-change cfg params)
        (change-email-immediately cfg params)))))

(defn- change-email-immediately
  [{:keys [conn]} {:keys [profile email] :as params}]
  (when (not= email (:email profile))
    (check-profile-existence! conn params))
  (db/update! conn :profile
              {:email email}
              {:id (:id profile)})
  {:changed true})

(defn- request-email-change
  [{:keys [conn tokens] :as cfg} {:keys [profile email] :as params}]
  (let [token   (tokens :generate
                        {:iss :change-email
                         :exp (dt/in-future "15m")
                         :profile-id (:id profile)
                         :email email})
        ptoken  (tokens :generate-predefined
                        {:iss :profile-identity
                         :profile-id (:id profile)})]

    (when (not= email (:email profile))
      (check-profile-existence! conn params))

    (when-not (eml/allow-send-emails? conn profile)
      (ex/raise :type :validation
                :code :profile-is-muted
                :hint "looks like the profile has reported repeatedly as spam or has permanent bounces."))

    (when (eml/has-bounce-reports? conn email)
      (ex/raise :type :validation
                :code :email-has-permanent-bounces
                :hint "looks like the email you invite has been repeatedly reported as spam or permanent bounce"))

    (eml/send! {::eml/conn conn
                ::eml/factory eml/change-email
                :public-uri (:public-uri cfg)
                :to (:email profile)
                :name (:fullname profile)
                :pending-email email
                :token token
                :extra-data ptoken})
    nil))


(defn select-profile-for-update
  [conn id]
  (db/get-by-id conn :profile id {:for-update true}))

;; --- MUTATION: Request Profile Recovery

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
            (let [ptoken (tokens :generate-predefined
                                 {:iss :profile-identity
                                  :profile-id (:id profile)})]
              (eml/send! {::eml/conn conn
                          ::eml/factory eml/password-recovery
                          :public-uri (:public-uri cfg)
                          :to (:email profile)
                          :token (:token profile)
                          :name (:fullname profile)
                          :extra-data ptoken})
              nil))]

    (db/with-atomic [conn pool]
      (when-let [profile (profile/retrieve-profile-data-by-email conn email)]
        (when-not (eml/allow-send-emails? conn profile)
          (ex/raise :type :validation
                    :code :profile-is-muted
                    :hint "looks like the profile has reported repeatedly as spam or has permanent bounces."))

        (when-not (:is-active profile)
          (ex/raise :type :validation
                    :code :profile-not-verified
                    :hint "the user need to validate profile before recover password"))

        (when (eml/has-bounce-reports? conn (:email profile))
          (ex/raise :type :validation
                    :code :email-has-permanent-bounces
                    :hint "looks like the email you invite has been repeatedly reported as spam or permanent bounce"))

        (->> profile
             (create-recovery-token)
             (send-email-notification conn))))))


;; --- MUTATION: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sv/defmethod ::recover-profile {:auth false :rlimit :password}
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

;; --- MUTATION: Update Profile Props

(s/def ::props map?)
(s/def ::update-profile-props
  (s/keys :req-un [::profile-id ::props]))

(sv/defmethod ::update-profile-props
  [{:keys [pool] :as cfg} {:keys [profile-id props]}]
  (db/with-atomic [conn pool]
    (let [profile (profile/retrieve-profile-data conn profile-id)
          props   (reduce-kv (fn [props k v]
                               ;; We don't accept namespaced keys
                               (if (simple-ident? k)
                                 (if (nil? v)
                                   (dissoc props k)
                                   (assoc props k v))
                                 props))
                             (:props profile)
                             props)]

      (db/update! conn :profile
                  {:props (db/tjson props)}
                  {:id profile-id})
      nil)))


;; --- MUTATION: Delete Profile

(declare check-can-delete-profile!)
(declare mark-profile-as-deleted!)

(s/def ::delete-profile
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::delete-profile
  [{:keys [pool session] :as cfg} {:keys [profile-id] :as params}]
  (db/with-atomic [conn pool]
    (check-can-delete-profile! conn profile-id)

    (db/update! conn :profile
                {:deleted-at (dt/now)}
                {:id profile-id})

    (with-meta {}
      {:transform-response (:delete session)})))

(def sql:owned-teams
  "with owner_teams as (
      select tpr.team_id as id
        from team_profile_rel as tpr
       where tpr.is_owner is true
         and tpr.profile_id = ?
   )
   select tpr.team_id,
          count(tpr.profile_id) as num_profiles
     from team_profile_rel as tpr
    where tpr.team_id in (select id from owner_teams)
    group by 1")

(defn- check-can-delete-profile!
  [conn profile-id]
  (let [rows (db/exec! conn [sql:owned-teams profile-id])]
    ;; If we found owned teams with more than one profile we don't
    ;; allow delete profile until the user properly transfer ownership
    ;; or explicitly removes all participants from the team.
    (when (some #(> (:num-profiles %) 1) rows)
      (ex/raise :type :validation
                :code :owner-teams-with-people
                :hint "The user need to transfer ownership of owned teams."
                :context {:teams (mapv :team-id rows)}))))
