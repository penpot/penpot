;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.users
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.media :as di]
   [app.main.data.websocket :as ws]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.util.i18n :as i18n]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; --- SCHEMAS

(def schema:profile
  [:map {:title "Profile"}
   [:id ::sm/uuid]
   [:created-at {:optional true} :any]
   [:fullname {:optional true} :string]
   [:email {:optional true} :string]
   [:lang {:optional true} :string]
   [:theme {:optional true} :string]])

(def profile?
  (sm/pred-fn schema:profile))

;; --- HELPERS

(defn is-authenticated?
  [{:keys [id]}]
  (and (uuid? id) (not= id uuid/zero)))

(defn get-current-team-id
  [profile]
  (let [team-id (::current-team-id @storage)]
    (or team-id (:default-team-id profile))))

(defn set-current-team!
  [team-id]
  (swap! storage assoc ::current-team-id team-id))

;; --- EVENT: fetch-teams

(defn teams-fetched
  [teams]
  (ptk/reify ::teams-fetched
    IDeref
    (-deref [_] teams)

    ptk/UpdateEvent
    (update [_ state]
      (assoc state :teams (d/index-by :id teams)))

    ptk/EffectEvent
    (effect [_ _ _]
      ;; Check if current team-id is part of available teams
      ;; if not, dissoc it from storage.

      (let [ids (into #{} (map :id) teams)]
        (when-let [ctid (::current-team-id @storage)]
          (when-not (contains? ids ctid)
            (swap! storage dissoc ::current-team-id)))))))

(defn fetch-teams
  []
  (ptk/reify ::fetch-teams
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-teams)
           (rx/map teams-fetched)))))

(defn set-current-team
  [team]
  (ptk/reify ::set-current-team
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :team team)
          (assoc :current-team-id (:id team))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (features/initialize (:features team #{}))))

    ptk/EffectEvent
    (effect [_ _ _]
      (set-current-team! (:id team)))))

;; --- EVENT: fetch-profile

(declare logout)

(def profile-fetched?
  (ptk/type? ::profile-fetched))

(defn profile-fetched
  [{:keys [id] :as profile}]
  (ptk/reify ::profile-fetched
    IDeref
    (-deref [_] profile)

    ptk/UpdateEvent
    (update [_ state]
      (cond-> state
        (is-authenticated? profile)
        (-> (assoc :profile-id id)
            (assoc :profile profile))))

    ptk/EffectEvent
    (effect [_ state _]
      (let [profile          (:profile state)
            email            (:email profile)
            previous-profile (:profile @storage)
            previous-email   (:email previous-profile)]
        (when profile
          (swap! storage assoc :profile profile)
          (i18n/set-locale! (:lang profile))
        (when (not= previous-email email)
          (swap! storage dissoc ::current-team-id)))))))

(defn fetch-profile
  []
  (ptk/reify ::fetch-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-profile)
           (rx/map profile-fetched)))))

;; --- EVENT: login

(defn- logged-in
  "This is the main event that is executed once we have logged in
  profile. The profile can proceed from standard login or from
  accepting invitation, or third party auth signup or singin."
  [profile]
  (letfn [(get-redirect-event []
            (let [team-id (get-current-team-id profile)
                  redirect-url (:redirect-url @storage)]
              (if (some? redirect-url)
                (do
                  (swap! storage dissoc :redirect-url)
                  (.replace js/location redirect-url))
                (rt/nav' :dashboard-projects {:team-id team-id}))))]

    (ptk/reify ::logged-in
      IDeref
      (-deref [_] profile)

      ptk/WatchEvent
      (watch [_ _ _]
        (when (is-authenticated? profile)
          (->> (rx/of (profile-fetched profile)
                      (fetch-teams)
                      (get-redirect-event)
                      (ws/initialize))
               (rx/observe-on :async)))))))

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
                              (rx/of (fetch-profile))
                              (->> stream
                                   (rx/filter profile-fetched?)
                                   (rx/take 1)
                                   (rx/map deref)
                                   (rx/filter (complement is-authenticated?))
                                   (rx/tap on-error)
                                   (rx/map #(ex/raise :type :authentication))
                                   (rx/observe-on :async))

                              (->> stream
                                   (rx/filter profile-fetched?)
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


(defn login-from-token
  [{:keys [profile] :as tdata}]
  (ptk/reify ::login-from-token
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (logged-in
              (with-meta profile
                {::ev/source "login-with-token"}))))))

(defn login-from-register
  "Event used mainly for mark current session as logged-in in after the
  user successfully registered using third party auth provider (in this
  case we dont need to verify the email)."
  []
  (ptk/reify ::login-from-register
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (fetch-profile))
       (->> stream
            (rx/filter (ptk/type? ::profile-fetched))
            (rx/take 1)
            (rx/map deref)
            (rx/map (fn [profile]
                      (with-meta profile
                        {::ev/source "register"})))
            (rx/map logged-in)
            (rx/observe-on :async))))))

;; --- EVENT: logout

(defn logged-out
  ([] (logged-out {}))
  ([_params]
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
       (swap! storage dissoc :redirect-url)
       (i18n/reset-locale)))))

(defn logout
  ([] (logout {}))
  ([params]
   (ptk/reify ::logout
     ptk/WatchEvent
     (watch [_ _ _]
       (->> (rp/cmd! :logout)
            (rx/delay-at-least 300)
            (rx/catch (constantly (rx/of 1)))
            (rx/map #(logged-out params)))))))

;; --- Update Profile

(defn update-profile
  [data]
  (dm/assert! (profile? data))
  (ptk/reify ::update-profile
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [mdata      (meta data)
            on-success (:on-success mdata identity)
            on-error   (:on-error mdata rx/throw)]
        (->> (rp/cmd! :update-profile (dissoc data :props))
             (rx/mapcat
              (fn [_]
                (rx/merge
                 (->> stream
                      (rx/filter (ptk/type? ::profile-fetched))
                      (rx/take 1)
                      (rx/tap on-success)
                      (rx/ignore))
                 (rx/of (profile-fetched data)))))
             (rx/catch on-error))))))



;; --- Request Email Change

(defn request-email-change
  [{:keys [email] :as data}]
  (dm/assert! ::us/email email)
  (ptk/reify ::request-email-change
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/cmd! :request-email-change data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Cancel Email Change

(def cancel-email-change
  (ptk/reify ::cancel-email-change
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :cancel-email-change {})
           (rx/map (constantly (fetch-profile)))))))

;; --- Update Password (Form)

(def schema:update-password
  [:map {:closed true}
   [:password-1 :string]
   [:password-2 :string]
   ;; Social registered users don't have old-password
   [:password-old {:optional true} [:maybe :string]]])

(defn update-password
  [data]
  (dm/assert! (sm/valid? schema:update-password data))
  (ptk/reify ::update-password
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)
            params {:old-password (:password-old data)
                    :password (:password-1 data)}]
        (->> (rp/cmd! :update-profile-password params)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty)))
             (rx/ignore))))))

(defn update-profile-props
  [props]
  (ptk/reify ::update-profile-props
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] merge props))

    ;; TODO: for the release 1.13 we should skip fetching profile and just use
    ;; the response value of update-profile-props RPC call
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-profile-props {:props props})
           (rx/map (constantly (fetch-profile)))))))

(defn mark-onboarding-as-viewed
  ([] (mark-onboarding-as-viewed nil))
  ([{:keys [version]}]
   (ptk/reify ::mark-onboarding-as-viewed
     ptk/WatchEvent
     (watch [_ _ _]
       (let [version (or version (:main cf/version))
             props   {:onboarding-viewed true
                      :release-notes-viewed version}]
         (->> (rp/cmd! :update-profile-props {:props props})
              (rx/map (constantly (fetch-profile)))))))))

(defn mark-questions-as-answered
  [onboarding-questions]
  (ptk/reify ::mark-questions-as-answered
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] assoc :onboarding-questions-answered true))

    ptk/WatchEvent
    (watch [_ _ _]
      (let [props {:onboarding-questions-answered true
                   :onboarding-questions onboarding-questions}]
        (->> (rp/cmd! :update-profile-props {:props props})
             (rx/map (constantly (fetch-profile))))))))


;; --- Update Photo

(defn update-photo
  [file]
  (dm/assert!
   "expected a valid blob for `file` param"
   (di/blob? file))

  (ptk/reify ::update-photo
    ptk/WatchEvent
    (watch [_ _ _]
      (let [on-success di/notify-finished-loading
            on-error #(do (di/notify-finished-loading)
                          (di/process-error %))

            prepare
            (fn [file]
              {:file file})]

        (di/notify-start-loading)
        (->> (rx/of file)
             (rx/map di/validate-file)
             (rx/map prepare)
             (rx/mapcat #(rp/cmd! :update-profile-photo %))
             (rx/do on-success)
             (rx/map (constantly (fetch-profile)))
             (rx/catch on-error))))))

(defn fetch-users
  [{:keys [team-id]}]
  (dm/assert! (uuid? team-id))
  (letfn [(fetched [users state]
            (->> users
                 (d/index-by :id)
                 (assoc state :users)))]
    (ptk/reify ::fetch-team-users
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/cmd! :get-team-users {:team-id team-id})
             (rx/map #(partial fetched %)))))))

(defn fetch-file-comments-users
  [{:keys [team-id]}]
  (dm/assert! (uuid? team-id))
  (letfn [(fetched [users state]
            (->> users
                 (d/index-by :id)
                 (assoc state :file-comments-users)))]
    (ptk/reify ::fetch-file-comments-users
      ptk/WatchEvent
      (watch [_ state _]
        (let [share-id (-> state :viewer-local :share-id)]
          (->> (rp/cmd! :get-profiles-for-file-comments {:team-id team-id :share-id share-id})
               (rx/map #(partial fetched %))))))))

;; --- EVENT: request-account-deletion

(defn request-account-deletion
  [params]
  (ptk/reify ::request-account-deletion
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta params)]
        (->> (rp/cmd! :delete-profile {})
             (rx/tap on-success)
             (rx/delay-at-least 300)
             (rx/catch (constantly (rx/of 1)))
             (rx/map logged-out)
             (rx/catch on-error))))))

;; --- EVENT: request-profile-recovery

(def schema:request-profile-recovery
  [:map {:closed true}
   [:email ::sm/email]])

;; FIXME: check if we can use schema for proper filter
(defn request-profile-recovery
  [data]
  (dm/assert! (sm/valid? schema:request-profile-recovery data))
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

(def schema:recover-profile
  [:map {:closed true}
   [:password :string]
   [:token :string]])

(defn recover-profile
  [data]
  (dm/assert! (sm/valid? schema:recover-profile data))
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

;; --- EVENT: fetch-team-webhooks

(defn access-tokens-fetched
  [access-tokens]
  (ptk/reify ::access-tokens-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :access-tokens access-tokens))))

(defn fetch-access-tokens
  []
  (ptk/reify ::fetch-access-tokens
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-access-tokens)
           (rx/map access-tokens-fetched)))))

;; --- EVENT: create-access-token

(defn access-token-created
  [access-token]
  (ptk/reify ::access-token-created
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :access-token-created access-token))))

(defn create-access-token
  [{:keys [] :as params}]
  (ptk/reify ::create-access-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :create-access-token params)
             (rx/map access-token-created)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: delete-access-token

(defn delete-access-token
  [{:keys [id] :as params}]
  (us/assert! ::us/uuid id)
  (ptk/reify ::delete-access-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-access-token params)
             (rx/tap on-success)
             (rx/catch on-error))))))
