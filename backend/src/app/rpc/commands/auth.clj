;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.auth
  (:require
   [app.auth :as auth]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.email.blacklist :as email.blacklist]
   [app.email.whitelist :as email.whitelist]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.setup :as-alias setup]
   [app.setup.welcome-file :refer [create-welcome-file]]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

(def schema:password
  [::sm/word-string {:max 500}])

(def schema:token
  [::sm/word-string {:max 6000}])

(defn- elapsed-verify-threshold?
  [profile]
  (let [elapsed (ct/diff (:modified-at profile) (ct/now))
        verify-threshold (cf/get :email-verify-threshold)]
    (pos? (compare elapsed verify-threshold))))

;; ---- COMMAND: login with password

(defn login-with-password
  [cfg {:keys [email password] :as params}]

  (when-not (or (contains? cf/flags :login)
                (contains? cf/flags :login-with-password))
    (ex/raise :type :restriction
              :code :login-disabled
              :hint "login is disabled"))

  (letfn [(check-password [cfg profile password]
            (if (= (:password profile) "!")
              (ex/raise :type :validation
                        :code :account-without-password
                        :hint "the current account does not have password")
              (let [result (auth/verify-password password (:password profile))]
                (when (:update result)
                  (l/trc :hint "updating profile password"
                         :id (str (:id profile))
                         :email (:email profile))
                  (profile/update-profile-password! cfg (assoc profile :password password)))
                (:valid result))))

          (validate-profile [cfg profile]
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when (:is-blocked profile)
              (ex/raise :type :restriction
                        :code :profile-blocked
                        :hint "profile is marked as blocked"))
            (when-not (check-password cfg profile password)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-let [deleted-at (:deleted-at profile)]
              (when (ct/is-after? (ct/now) deleted-at)
                (ex/raise :type :validation
                          :code :wrong-credentials)))

            profile)

          (login [{:keys [::db/conn] :as cfg}]
            (let [profile    (->> (profile/clean-email email)
                                  (profile/get-profile-by-email conn)
                                  (validate-profile cfg)
                                  (profile/strip-private-attrs))

                  invitation (when-let [token (:invitation-token params)]
                               (tokens/verify (::setup/props cfg) {:token token :iss :team-invitation}))

                  ;; If invitation member-id does not matches the profile-id, we just proceed to ignore the
                  ;; invitation because invitations matches exactly; and user can't login with other email and
                  ;; accept invitation with other email
                  response   (if (and (some? invitation) (= (:id profile) (:member-id invitation)))
                               {:invitation-token (:invitation-token params)}
                               (assoc profile :is-admin (let [admins (cf/get :admins)]
                                                          (contains? admins (:email profile)))))]
              (-> response
                  (rph/with-transform (session/create-fn cfg (:id profile)))
                  (rph/with-meta {::audit/props (audit/profile->props profile)
                                  ::audit/profile-id (:id profile)}))))]

    (db/tx-run! cfg login)))

(def schema:login-with-password
  [:map {:title "login-with-password"}
   [:email ::sm/email]
   [:password schema:password]
   [:invitation-token {:optional true} schema:token]])

(sv/defmethod ::login-with-password
  "Performs authentication using penpot password."
  {::rpc/auth false
   ::doc/added "1.15"
   ::climit/id :auth/global
   ::sm/params schema:login-with-password}
  [cfg params]
  (login-with-password cfg params))

;; ---- COMMAND: Logout

(def ^:private schema:logout
  [:map {:title "logoug"}
   [:profile-id {:optional true} ::sm/uuid]])

(sv/defmethod ::logout
  "Clears the authentication cookie and logout the current session."
  {::rpc/auth false
   ::doc/changes [["2.1" "Now requires profile-id passed in the body"]]
   ::doc/added "1.0"
   ::sm/params schema:logout}
  [cfg params]
  (if (= (:profile-id params)
         (::rpc/profile-id params))
    (rph/with-transform {} (session/delete-fn cfg))
    {}))

;; ---- COMMAND: Recover Profile

(defn recover-profile
  [{:keys [::db/conn] :as cfg} {:keys [token password]}]
  (letfn [(validate-token [token]
            (let [tdata (tokens/verify (::setup/props cfg) {:token token :iss :password-recovery})]
              (:profile-id tdata)))

          (update-password [conn profile-id]
            (let [pwd (auth/derive-password password)]
              (db/update! conn :profile {:password pwd :is-active true} {:id profile-id})
              nil))]

    (->> (validate-token token)
         (update-password conn))

    nil))

(def schema:recover-profile
  [:map {:title "recover-profile"}
   [:token schema:token]
   [:password schema:password]])

(sv/defmethod ::recover-profile
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:recover-profile
   ::climit/id :auth/global
   ::db/transaction true}
  [cfg params]
  (recover-profile cfg params))

;; ---- COMMAND: Prepare Register

(defn- validate-register-attempt!
  [cfg params]

  (when (or (not (contains? cf/flags :registration))
            (not (contains? cf/flags :login-with-password)))
    (ex/raise :type :restriction
              :code :registration-disabled
              :hint "registration disabled"))

  (when (contains? params :invitation-token)
    (let [invitation (tokens/verify (::setup/props cfg)
                                    {:token (:invitation-token params)
                                     :iss :team-invitation})]
      (when-not (= (:email params) (:member-email invitation))
        (ex/raise :type :restriction
                  :code :email-does-not-match-invitation
                  :hint "email should match the invitation"))))

  (when (and (email.blacklist/enabled? cfg)
             (email.blacklist/contains? cfg (:email params)))
    (ex/raise :type :restriction
              :code :email-domain-is-not-allowed
              :hint "email domain in blacklist"))

  (when (and (email.whitelist/enabled? cfg)
             (not (email.whitelist/contains? cfg (:email params))))
    (ex/raise :type :restriction
              :code :email-domain-is-not-allowed
              :hint "email domain not in whitelist"))

  ;; Perform a basic validation of email & password
  (when (= (str/lower (:email params))
           (str/lower (:password params)))
    (ex/raise :type :validation
              :code :email-as-password
              :hint "you can't use your email as password"))

  (when (eml/has-bounce-reports? cfg (:email params))
    (ex/raise :type :restriction
              :code :email-has-permanent-bounces
              :email (:email params)
              :hint "email has bounce reports"))

  (when (eml/has-complaint-reports? cfg (:email params))
    (ex/raise :type :restriction
              :code :email-has-complaints
              :email (:email params)
              :hint "email has complaint reports")))

(defn prepare-register
  [{:keys [::db/pool] :as cfg} {:keys [fullname email accept-newsletter-updates] :as params}]

  (validate-register-attempt! cfg params)

  (let [email   (profile/clean-email email)
        profile (profile/get-profile-by-email pool email)
        params  {:email email
                 :fullname fullname
                 :password (:password params)
                 :invitation-token (:invitation-token params)
                 :backend "penpot"
                 :iss :prepared-register
                 :profile-id (:id profile)
                 :exp (ct/in-future {:days 7})
                 :props {:newsletter-updates (or accept-newsletter-updates false)}}

        params (d/without-nils params)
        token  (tokens/generate (::setup/props cfg) params)]

    (with-meta {:token token}
      {::audit/profile-id uuid/zero})))

(def schema:prepare-register-profile
  [:map {:title "prepare-register-profile"}
   [:fullname ::sm/text]
   [:email ::sm/email]
   [:password schema:password]
   [:create-welcome-file {:optional true} :boolean]
   [:invitation-token {:optional true} schema:token]])

(sv/defmethod ::prepare-register-profile
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:prepare-register-profile}
  [cfg params]
  (prepare-register cfg params))

;; ---- COMMAND: Register Profile

(defn create-profile!
  "Create the profile entry on the database with limited set of input
  attrs (all the other attrs are filled with default values)."
  [conn {:keys [email] :as params}]
  (dm/assert! ::sm/email email)
  (let [id        (or (:id params) (uuid/next))
        props     (-> (audit/extract-utm-params params)
                      (merge (:props params))
                      (merge {:viewed-tutorial? false
                              :viewed-walkthrough? false
                              :nudge {:big 10 :small 1}
                              :v2-info-shown true
                              :release-notes-viewed (:main cf/version)})
                      (db/tjson))

        password  (or (:password params) "!")

        locale    (:locale params)
        locale    (when (and (string? locale) (not (str/blank? locale)))
                    locale)

        backend   (:backend params "penpot")
        is-demo   (:is-demo params false)
        is-muted  (:is-muted params false)
        is-active (:is-active params false)
        theme     (:theme params nil)
        email     (str/lower email)

        params    {:id id
                   :fullname (:fullname params)
                   :email email
                   :auth-backend backend
                   :lang locale
                   :password password
                   :deleted-at (:deleted-at params)
                   :props props
                   :theme theme
                   :is-active is-active
                   :is-muted is-muted
                   :is-demo is-demo}]
    (try
      (-> (db/insert! conn :profile params)
          (profile/decode-row))
      (catch org.postgresql.util.PSQLException cause
        (let [state (.getSQLState cause)]
          (if (not= state "23505")
            (throw cause)

            (do
              (l/error :hint "not an error" :cause cause)
              (ex/raise :type :validation
                        :code :email-already-exists
                        :hint "email already exists"
                        :cause cause))))))))

(defn create-profile-rels!
  [conn {:keys [id] :as profile}]
  (let [features (cfeat/get-enabled-features cf/flags)
        team     (teams/create-team conn
                                    {:profile-id id
                                     :name "Default"
                                     :features features
                                     :is-default true})]
    (-> (db/update! conn :profile
                    {:default-team-id (:id team)
                     :default-project-id  (:default-project-id team)}
                    {:id id}
                    {::db/return-keys true})
        (profile/decode-row))))

(defn send-email-verification!
  [{:keys [::db/conn] :as cfg} profile]
  (let [vtoken (tokens/generate (::setup/props cfg)
                                {:iss :verify-email
                                 :exp (ct/in-future "72h")
                                 :profile-id (:id profile)
                                 :email (:email profile)})
        ;; NOTE: this token is mainly used for possible complains
        ;; identification on the sns webhook
        ptoken (tokens/generate (::setup/props cfg)
                                {:iss :profile-identity
                                 :profile-id (:id profile)
                                 :exp (ct/in-future {:days 30})})]
    (eml/send! {::eml/conn conn
                ::eml/factory eml/register
                :public-uri (cf/get :public-uri)
                :to (:email profile)
                :name (:fullname profile)
                :token vtoken
                :extra-data ptoken})))

(defn register-profile
  [{:keys [::db/conn ::wrk/executor] :as cfg} {:keys [token] :as params}]
  (let [claims     (tokens/verify (::setup/props cfg) {:token token :iss :prepared-register})
        params     (into claims params)

        profile    (if-let [profile-id (:profile-id claims)]
                     (profile/get-profile conn profile-id)
                     ;; NOTE: we first try to match existing profile
                     ;; by email, that in normal circumstances will
                     ;; not return anything, but when a user tries to
                     ;; reuse the same token multiple times, we need
                     ;; to detect if the profile is already registered
                     (or (profile/get-profile-by-email conn (:email claims))
                         (let [is-active (or (boolean (:is-active claims))
                                             (not (contains? cf/flags :email-verification)))
                               params    (-> params
                                             (assoc :is-active is-active)
                                             (update :password auth/derive-password))
                               profile   (->> (create-profile! conn params)
                                              (create-profile-rels! conn))]
                           (vary-meta profile assoc :created true))))

        created?   (-> profile meta :created true?)

        invitation (when-let [token (:invitation-token params)]
                     (tokens/verify (::setup/props cfg) {:token token :iss :team-invitation}))

        props      (-> (audit/profile->props profile)
                       (assoc :from-invitation (some? invitation)))


        create-welcome-file-when-needed
        (fn []
          (when (:create-welcome-file params)
            (let [cfg (dissoc cfg ::db/conn)]
              (wrk/submit! executor (create-welcome-file cfg profile)))))]
    (cond
      ;; When profile is blocked, we just ignore it and return plain data
      (:is-blocked profile)
      (do
        (l/wrn :hint "register attempt for already blocked profile"
               :profile-id (str  (:id profile))
               :profile-email (:email profile))
        (rph/with-meta {:email (:email profile)}
          {::audit/replace-props props
           ::audit/context {:action "ignore-because-blocked"}
           ::audit/profile-id (:id profile)
           ::audit/name "register-profile-retry"}))

      ;; If invitation token comes in params, this is because the user
      ;; comes from team-invitation process; in this case, regenerate
      ;; token and send back to the user a new invitation token (and
      ;; mark current session as logged). This happens only if the
      ;; invitation email matches with the register email.
      (and (some? invitation)
           (= (:email profile)
              (:member-email invitation)))
      (let [claims (assoc invitation :member-id  (:id profile))
            token  (tokens/generate (::setup/props cfg) claims)]
        (-> {:invitation-token token}
            (rph/with-transform (session/create-fn cfg (:id profile)))
            (rph/with-meta {::audit/replace-props props
                            ::audit/context {:action "accept-invitation"}
                            ::audit/profile-id (:id profile)})))

      ;; When a new user is created and it is already activated by
      ;; configuration or specified by OIDC, we just mark the profile
      ;; as logged-in
      created?
      (if (:is-active profile)
        (-> (profile/strip-private-attrs profile)
            (rph/with-transform (session/create-fn cfg (:id profile)))
            (rph/with-defer create-welcome-file-when-needed)
            (rph/with-meta
              {::audit/replace-props props
               ::audit/context {:action "login"}
               ::audit/profile-id (:id profile)}))

        (do
          (when-not (eml/has-reports? conn (:email profile))
            (send-email-verification! cfg profile))

          (-> {:email (:email profile)}
              (rph/with-defer create-welcome-file-when-needed)
              (rph/with-meta
                {::audit/replace-props props
                 ::audit/context {:action "email-verification"}
                 ::audit/profile-id (:id profile)}))))

      :else
      (let [elapsed? (elapsed-verify-threshold? profile)
            reports? (eml/has-reports? conn (:email profile))
            action   (if reports?
                       "ignore-because-complaints"
                       (if elapsed?
                         "resend-email-verification"
                         "ignore"))]

        (l/wrn :hint "repeated registry detected"
               :profile-id (str (:id profile))
               :profile-email (:email profile)
               :context-action action)

        (when (= action "resend-email-verification")
          (db/update! conn :profile
                      {:modified-at (ct/now)}
                      {:id (:id profile)})
          (send-email-verification! cfg profile))

        (rph/with-meta {:email (:email profile)}
          {::audit/replace-props (audit/profile->props profile)
           ::audit/context {:action action}
           ::audit/profile-id (:id profile)
           ::audit/name "register-profile-retry"})))))

(def schema:register-profile
  [:map {:title "register-profile"}
   [:token schema:token]])

(sv/defmethod ::register-profile
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:register-profile
   ::climit/id :auth/global}
  [cfg params]
  (db/tx-run! cfg register-profile params))

;; ---- COMMAND: Request Profile Recovery

(defn- request-profile-recovery
  [{:keys [::db/conn] :as cfg} {:keys [email] :as params}]
  (letfn [(create-recovery-token [{:keys [id] :as profile}]
            (let [token (tokens/generate (::setup/props cfg)
                                         {:iss :password-recovery
                                          :exp (ct/in-future "15m")
                                          :profile-id id})]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (let [ptoken (tokens/generate (::setup/props cfg)
                                          {:iss :profile-identity
                                           :profile-id (:id profile)
                                           :exp (ct/in-future {:days 30})})]
              (eml/send! {::eml/conn conn
                          ::eml/factory eml/password-recovery
                          :public-uri (cf/get :public-uri)
                          :to (:email profile)
                          :token (:token profile)
                          :name (:fullname profile)
                          :extra-data ptoken})
              nil))]

    (let [profile (->> (profile/clean-email email)
                       (profile/get-profile-by-email conn))]

      (cond
        (not profile)
        (l/wrn :hint "attempt of profile recovery: no profile found"
               :profile-email email)

        (not (eml/allow-send-emails? conn profile))
        (l/wrn :hint "attempt of profile recovery: profile is muted"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        (eml/has-bounce-reports? conn (:email profile))
        (l/wrn :hint "attempt of profile recovery: email has bounces"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        (eml/has-complaint-reports? conn (:email profile))
        (l/wrn :hint "attempt of profile recovery: email has complaints"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        (not (elapsed-verify-threshold? profile))
        (l/wrn :hint "attempt of profile recovery: retry attempt threshold not elapsed"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        :else
        (do
          (db/update! conn :profile
                      {:modified-at (ct/now)}
                      {:id (:id profile)})
          (->> profile
               (create-recovery-token)
               (send-email-notification conn)))))))

(def schema:request-profile-recovery
  [:map {:title "request-profile-recovery"}
   [:email ::sm/email]])

(sv/defmethod ::request-profile-recovery
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:request-profile-recovery}
  [cfg params]
  (db/tx-run! cfg request-profile-recovery params))


