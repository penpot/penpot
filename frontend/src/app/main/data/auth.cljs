;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.auth
  "Auth related data events"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
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

;; --- EVENT: login

(defn- logged-in
  "This is the main event that is executed once we have logged in
  profile. The profile can proceed from standard login or from
  accepting invitation, or third party auth signup or singin."
  [{:keys [props] :as profile}]
  (letfn [(get-redirect-events [teams]
            (if-let [token (:invitation-token profile)]
              (rx/of (rt/nav :auth-verify-token {:token token}))
              (if-let [redirect-href (:login-redirect storage/session)]
                (binding [storage/*sync* true]
                  (swap! storage/session dissoc :login-redirect)
                  (if (= redirect-href (rt/get-current-href))
                    (rx/of (rt/reload true))
                    (rx/of (rt/nav-raw :href redirect-href))))
                (if-let [file-id (get props :welcome-file-id)]
                  (rx/of (dcm/go-to-workspace
                          :file-id file-id
                          :team-id (:default-team-id profile))
                         (dp/update-profile-props {:welcome-file-id nil}))

                  (let [teams   (into #{} (map :id) teams)
                        team-id (dtm/get-last-team-id)
                        team-id (if (and team-id (contains? teams team-id))
                                  team-id
                                  (:default-team-id profile))]
                    (rx/of (dcm/go-to-dashboard-recent {:team-id team-id})))))))]

    (ptk/reify ::logged-in
      ev/Event
      (-data [_]
        {::ev/name "signin"
         ::ev/type "identify"
         :email (:email profile)
         :auth-backend (:auth-backend profile)
         :fullname (:fullname profile)
         :is-muted (:is-muted profile)
         :default-team-id (:default-team-id profile)
         :default-project-id (:default-project-id profile)})

      ptk/WatchEvent
      (watch [_ _ stream]
        (cf/initialize-external-context-info)

        (->> (rx/merge
              (rx/of (dp/set-profile profile)
                     (ws/initialize)
                     (dtm/fetch-teams))

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
                                               {::ev/source "login"})))
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

  (dm/assert!
   "expected valid params"
   (sm/check schema:login-with-ldap params))

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
                           (with-meta {::ev/source "login-with-ldap"})
                           (logged-in))))
             (rx/catch on-error))))))

(defn login-from-token
  "Used mainly as flow continuation after token validation."
  [{:keys [profile] :as tdata}]
  (ptk/reify ::login-from-token
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rx/of (logged-in (with-meta profile {::ev/source "login-with-token"})))
           ;; NOTE: we need this to be asynchronous because the effect
           ;; should be called before proceed with the login process
           (rx/observe-on :async)))))

(defn login-from-register
  "Event used mainly for mark current session as logged-in in after the
  user successfully registered using third party auth provider (in this
  case we dont need to verify the email)."
  []
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
            (rx/map (fn [profile]
                      (with-meta profile
                        {::ev/source "register"})))
            (rx/map logged-in)
            (rx/observe-on :async))))))

;; --- EVENT: logout

(defn logged-out
  []
  (ptk/reify ::logged-out
    ptk/UpdateEvent
    (update [_ state]
      (select-keys state [:route :router :session-id :history]))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/merge
       ;; NOTE: We need the `effect` of the current event to be
       ;; executed before the redirect.
       (->> (rx/of (rt/nav :auth-login))
            (rx/observe-on :async))
       (rx/of (ws/finalize))))

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
                               (rx/catch (constantly (rx/of 1))))))
             (rx/map logged-out))))))

;; --- Update Profile

(def ^:private
  schema:request-profile-recovery
  [:map {:title "request-profile-recovery" :closed true}
   [:email ::sm/email]])

(defn request-profile-recovery
  [data]

  (dm/assert!
   "expected valid parameters"
   (sm/check schema:request-profile-recovery data))

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
  (dm/assert!
   "expected valid arguments"
   (sm/check schema:recover-profile data))

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
