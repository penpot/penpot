;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.auth
  "Auth related data events"
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as dp]
   [app.main.data.team :as dtm]
   [app.main.data.websocket :as ws]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- HELPERS

(defn is-authenticated?
  [{:keys [id]}]
  (and (uuid? id) (not= id uuid/zero)))

;; --- AUTH ORIGIN

(def schema:auth-origin
  [:map
   [:source          [:enum "login" "register"]]
   [:auth-backend    [:enum "penpot" "ldap" "google" "github" "gitlab" "oidc"]]
   [:activation      {:optional true} [:enum "immediate" "email-verification"]]
   [:from-invitation {:optional true} :boolean]])

(defn auth-origin
  "Canonical description of how an auth flow happened. The fields that
  do not apply are omitted: a login carries no `:activation`."
  [{:keys [source auth-backend activation from-invitation]}]
  (let [origin (d/without-nils
                {:source          source
                 :auth-backend    auth-backend
                 :activation      activation
                 :from-invitation from-invitation})]
    (assert (sm/check schema:auth-origin origin) "invalid auth-origin")
    origin))

(defn- profile-origin
  [profile]
  (let [m (meta profile)]
    (auth-origin
     {:source          (::ev/source m)
      :activation      (::ev/activation m)
      :auth-backend    (:auth-backend profile)
      :from-invitation (some? (:invitation-token profile))})))

(def ^:private iss->origin
  {:auth         {:source "login"}
   :verify-email {:source "register" :activation "email-verification"}})

(defn- origin-meta
  [{:keys [source activation]}]
  (d/without-nils {::ev/source source
                   ::ev/activation activation}))

;; --- EVENT: login

(defn- get-invitation-nav
  "Observable that emits the navigation to the token verification screen
  when the profile carries an invitation token, and nothing otherwise.

  It does not depend on the teams: `fetch-teams` has no error handling,
  so waiting for it would leave the invitation unaccepted when that call
  fails."
  [profile]
  (->> (rx/of (:invitation-token profile))
       (rx/filter some?)
       (rx/map #(rt/nav :auth-verify-token {:token %}))))

(defn- logged-in
  "This is the main event that is executed once we have logged in
  profile. The profile can proceed from standard login or from
  accepting invitation, or third party auth signup or singin."
  [profile]
  (letfn [(get-redirect-events [teams]
            (if (some? (:invitation-token profile))
              ;; The invitation navigates on its own, without waiting for
              ;; the teams; this branch must not move the user away from
              ;; the token verification screen.
              (rx/empty)
              (if-let [redirect-href (:login-redirect storage/session)]
                (binding [storage/*sync* true]
                  (swap! storage/session dissoc :login-redirect)
                  (if (= redirect-href (rt/get-current-href))
                    (rx/of (rt/reload true))
                    (rx/of (rt/nav-raw :href redirect-href))))
                (let [default-team-id (:default-team-id profile)
                      team-ids        (into #{} (map :id) teams)
                      team-id         (dtm/get-last-team-id)
                      team-id         (if (and team-id (contains? team-ids team-id))
                                        team-id
                                        default-team-id)]
                  (->> (dtm/resolve-login-team-id {:team-id team-id
                                                   :default-team-id default-team-id})
                       (rx/mapcat (fn [team-id]
                                    (rx/of (dcm/go-to-dashboard-recent {:team-id team-id})))))))))]

    (ptk/reify ::logged-in
      ptk/WatchEvent
      (watch [_ _ stream]
        (cf/initialize-external-context-info)


        (->> (rx/merge
              (rx/of (dp/set-profile profile)
                     (ws/initialize)
                     (dtm/fetch-teams))

              ;; The invitation resolves on its own, without waiting for the
              ;; teams.
              (->> (get-invitation-nav profile)
                   (rx/observe-on :async))

              ;; We schedule this event to be executed a bit later,
              ;; when the profile is already set
              (->> (rx/of (ev/event (merge {::ev/name "signin"
                                            ::ev/type "identify"
                                            :id (:id profile)
                                            :email (:email profile)
                                            :fullname (:fullname profile)
                                            :is-muted (:is-muted profile)
                                            :default-team-id (:default-team-id profile)
                                            :default-project-id (:default-project-id profile)}
                                           (profile-origin profile))))
                   (rx/observe-on :async))


              (->> stream
                   (rx/filter (ptk/type? ::dtm/teams-fetched))
                   (rx/take 1)
                   (rx/map deref)
                   (rx/mapcat get-redirect-events)))

             (rx/observe-on :async))))))

(declare login-from-register)

(defn login
  [{:keys [email password invitation-token] :as data}]
  (ptk/reify ::login
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)

            params {:email email
                    :password password
                    :invitation-token invitation-token}]

        ;; NOTE: We can't take the profile value from login because
        ;; there are cases when login is successful but the cookie is
        ;; not set properly (because of possible misconfiguration).
        ;; So, we proceed to make an additional call to fetch the
        ;; profile, and ensure that cookie is set correctly. If
        ;; profile fetch is successful, we mark the user logged in, if
        ;; the returned profile is an NOT authenticated profile, we
        ;; proceed to logout and show an error message.

        (->> (rp/cmd! :login-with-password (d/without-nils params))
             (rx/merge-map (fn [data]
                             (rx/merge
                              (rx/of (dp/fetch-profile))
                              (->> stream
                                   (rx/filter dp/profile-fetched?)
                                   (rx/take 1)
                                   (rx/map deref)
                                   (rx/filter (complement is-authenticated?))
                                   (rx/tap on-error)
                                   (rx/map #(ex/raise :type :authentication))
                                   (rx/observe-on :async))

                              (->> stream
                                   (rx/filter dp/profile-fetched?)
                                   (rx/take 1)
                                   (rx/map deref)
                                   (rx/filter is-authenticated?)
                                   (rx/map (fn [profile]
                                             (with-meta (merge data profile)
                                               (origin-meta {:source "login"}))))
                                   (rx/tap on-success)
                                   (rx/map logged-in)
                                   (rx/observe-on :async)))))
             (rx/catch on-error))))))

(def ^:private schema:login-with-ldap
  [:map {:title "login-with-ldap"}
   [:email ::sm/email]
   [:password :string]])

(defn login-with-ldap
  [params]

  (assert (sm/check schema:login-with-ldap params))

  (ptk/reify ::login-with-ldap
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta params)]
        (->> (rp/cmd! :login-with-ldap params)
             (rx/tap on-success)
             (rx/map (fn [profile]
                       (-> profile
                           (with-meta (origin-meta {:source "login"}))
                           (logged-in))))
             (rx/catch on-error))))))

(def ^:private schema:login-with-sso
  [:map {:title "login-with-sso"}
   [:provider [:or :string ::sm/uuid]]])

(defn login-with-sso
  "Start the SSO flow"
  [params]
  (assert (sm/check schema:login-with-sso params))
  (ptk/reify ::login-with-sso
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :login-with-oidc params)
           (rx/map (fn [{:keys [redirect-uri] :as rsp}]
                     (if redirect-uri
                       (rt/nav-raw :uri redirect-uri)
                       (ex/raise :type :assertion
                                 :code :unexpected-response
                                 :hint "unexpected response from OIDC method"
                                 :resp (pr-str rsp)))))
           (rx/catch (fn [cause]
                       (let [{:keys [type code] :as error} (ex-data cause)]
                         (if (and (= type :restriction)
                                  (= code :provider-not-configured))
                           (rx/of (ntf/error (tr "errors.auth-provider-not-configured")))
                           (rx/throw cause)))))))))

(defn login-from-token
  "Used mainly as flow continuation after token validation."
  [{:keys [profile invitation-token iss] :as tdata}]
  (ptk/reify ::login-from-token
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (dp/on-fetch-profile-success profile)
           (rx/map (fn [profile]
                     (let [profile (cond-> profile
                                     invitation-token
                                     (assoc :invitation-token invitation-token)

                                     :always
                                     (with-meta (origin-meta (iss->origin iss))))]
                       (logged-in profile))))))))

(defn- with-register-origin
  [profile invitation-token]
  (-> profile
      (cond-> (some? invitation-token)
        (assoc :invitation-token invitation-token))
      (with-meta (origin-meta {:source "register" :activation "immediate"}))))

(defn login-from-register
  "Event used mainly for mark current session as logged-in in after the
  user successfully registered using third party auth provider (in this
  case we dont need to verify the email)."
  ([] (login-from-register nil))
  ([{:keys [invitation-token]}]
   (ptk/reify ::login-from-register
     ptk/WatchEvent
     (watch [_ _ stream]
       (rx/merge
        (rx/of (dp/fetch-profile))
        (->> stream
             (rx/filter dp/profile-fetched?)
             (rx/take 1)
             (rx/map deref)
             (rx/filter is-authenticated?)
             (rx/map #(with-register-origin % invitation-token))
             (rx/map logged-in)
             (rx/observe-on :async)))))))

;; --- EVENT: logout

(defn logged-out
  [{:keys [redirect-uri]}]
  (ptk/reify ::logged-out
    ptk/UpdateEvent
    (update [_ state]
      (select-keys state [:route :router :session-id]))

    ptk/WatchEvent
    (watch [_ _ _]
      (if redirect-uri
        (->> (rx/of (rt/nav-raw :uri (str redirect-uri)))
             (rx/observe-on :async))

        (rx/merge
         ;; NOTE: We need the `effect` of the current event to be
         ;; executed before the redirect.
         (->> (rx/of (rt/nav :auth-login))
              (rx/observe-on :async))
         (rx/of (ws/finalize)))))

    ptk/EffectEvent
    (effect [_ _ _]
      ;; We prefer to keek some stuff in the storage like the current-team-id and the profile
      (swap! storage/user (constantly {})))))

(defn logout
  []
  (ptk/reify ::logout
    ev/Event
    (-data [_] {})

    ptk/WatchEvent
    (watch [_ state _]
      (let [profile-id (:profile-id state)]
        (->> (rx/interval 500)
             (rx/take 1)
             (rx/mapcat (fn [_]
                          (->> (rp/cmd! :logout {:profile-id profile-id})
                               (rx/delay-at-least 300)
                               (rx/catch (constantly (rx/of nil))))))
             (rx/map logged-out))))))

;; --- Update Profile

(def ^:private
  schema:request-profile-recovery
  [:map {:title "request-profile-recovery" :closed true}
   [:email ::sm/email]])

(defn request-profile-recovery
  [data]

  (assert (sm/check schema:request-profile-recovery data))

  (ptk/reify ::request-profile-recovery
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)]

        (->> (rp/cmd! :request-profile-recovery data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: recover-profile (Password)

(def ^:private
  schema:recover-profile
  [:map {:title "recover-profile" :closed true}
   [:password :string]
   [:token :string]])

(defn recover-profile
  [data]
  (assert (sm/check schema:recover-profile data))

  (ptk/reify ::recover-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)]
        (->> (rp/cmd! :recover-profile data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: crete-demo-profile

(defn create-demo-profile
  []
  (ptk/reify ::create-demo-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :create-demo-profile {})
           (rx/map login)))))

(defn show-redirect-error
  "A helper event that interprets the OIDC redirect errors on the URI
  and shows an appropriate error message using the notification
  banners."
  [error]
  (ptk/reify ::show-redirect-error
    ptk/WatchEvent
    (watch [_ _ _]
      (when-let [hint (case error
                        "registration-disabled"
                        (tr "errors.registration-disabled")
                        "profile-blocked"
                        (tr "errors.profile-blocked")
                        "auth-provider-not-allowed"
                        (tr "errors.auth-provider-not-allowed")
                        "email-domain-not-allowed"
                        (tr "errors.email-domain-not-allowed")

                        ;; We explicitly do not show any error here, it a explicit user operation.
                        "unable-to-auth"
                        nil

                        (tr "errors.generic"))]

        (rx/of (ntf/warn hint))))))
