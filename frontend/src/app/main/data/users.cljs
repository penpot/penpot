;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.users
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.media :as di]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.util.i18n :as i18n]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [app.util.theme :as theme]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; --- COMMON SPECS

(defn is-authenticated?
  [{:keys [id]}]
  (and (uuid? id) (not= id uuid/zero)))

(s/def ::id ::us/uuid)
(s/def ::fullname ::us/string)
(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::lang (s/nilable ::us/string))
(s/def ::theme (s/nilable ::us/string))
(s/def ::created-at ::us/inst)
(s/def ::password-1 ::us/string)
(s/def ::password-2 ::us/string)
(s/def ::password-old ::us/string)

(s/def ::profile
  (s/keys :req-un [::id]
          :opt-un [::created-at
                   ::fullname
                   ::email
                   ::lang
                   ::theme]))

;; --- HELPERS

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
  (let [teams (d/index-by :id teams)
        ids   (into #{} (keys teams))]

    (ptk/reify ::teams-fetched
      IDeref
      (-deref [_] teams)

      ptk/UpdateEvent
      (update [_ state]
        (assoc state :teams teams))

      ptk/EffectEvent
      (effect [_ _ _]
        ;; Check if current team-id is part of available teams
        ;; if not, dissoc it from storage.
        (when-let [ctid (::current-team-id @storage)]
          (when-not (contains? ids ctid)
            (swap! storage dissoc ::current-team-id)))))))



(defn fetch-teams
  []
  (ptk/reify ::fetch-teams
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/query! :teams)
           (rx/map teams-fetched)))))

;; --- EVENT: fetch-profile

(def profile-fetched?
  (ptk/type? ::profile-fetched))

(defn profile-fetched
  [{:keys [id] :as profile}]
  (us/verify ::profile profile)
  (ptk/reify ::profile-fetched
    IDeref
    (-deref [_] profile)

    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :profile-id id)
          (assoc :profile profile)))

    ptk/EffectEvent
    (effect [_ state _]
      (let [profile (:profile state)]
        (when (not= uuid/zero (:id profile))
          (swap! storage assoc :profile profile)
          (i18n/set-locale! (:lang profile))
          (some-> (:theme profile)
                  (theme/set-current-theme!)))))))

(defn fetch-profile
  []
  (ptk/reify ::fetch-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/query! :profile)
           (rx/map profile-fetched)))))

;; --- EVENT: INITIALIZE PROFILE

(defn initialize-profile
  "Event used mainly on application bootstrap; it fetches the profile
  and if and only if the fetched profile corresponds to an
  authenticated user; proceed to fetch teams."
  []
  (ptk/reify ::initialize-profile
    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (fetch-profile))
       (->> stream
            (rx/filter (ptk/type? ::profile-fetched))
            (rx/take 1)
            (rx/map deref)
            (rx/mapcat (fn [profile]
                         (if (= uuid/zero (:id profile))
                           (rx/empty)
                           (rx/of (fetch-teams))))))))))

;; --- EVENT: login

(defn- logged-in
  [profile]
  (ptk/reify ::logged-in
    IDeref
    (-deref [_] profile)

    ptk/WatchEvent
    (watch [_ _ _]
      (let [team-id (get-current-team-id profile)]
        (->> (rx/concat
              (rx/of (profile-fetched profile)
                     (fetch-teams))

              (->> (rx/of (rt/nav' :dashboard-projects {:team-id team-id}))
                   (rx/delay 1000))

              (when-not (get-in profile [:props :onboarding-viewed])
                (->> (rx/of (modal/show {:type :onboarding}))
                     (rx/delay 1000))))

             (rx/observe-on :async))))))

(s/def ::login-params
  (s/keys :req-un [::email ::password]))

(defn login
  [{:keys [email password] :as data}]
  (us/verify ::login-params data)
  (ptk/reify ::login
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)
            params {:email email
                    :password password
                    :scope "webapp"}]
        (->> (rx/timer 100)
             (rx/mapcat #(rp/mutation :login params))
             (rx/tap on-success)
             (rx/catch on-error)
             (rx/map (fn [profile]
                       (with-meta profile
                         {::ev/source "login"})))
             (rx/map logged-in))))))

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
            (rx/map logged-in))))))

;; --- EVENT: logout

(defn logged-out
  []
  (ptk/reify ::logged-out
    ptk/UpdateEvent
    (update [_ state]
      (select-keys state [:route :router :session-id :history]))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (rt/nav :auth-login)))

    ptk/EffectEvent
    (effect [_ _ _]
      (reset! storage {})
      (i18n/reset-locale))))

(defn logout
  []
  (ptk/reify ::logout
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation :logout)
           (rx/delay-at-least 300)
           (rx/catch (constantly (rx/of 1)))
           (rx/map logged-out)))))

;; --- EVENT: register

;; TODO: remove
(s/def ::invitation-token ::us/not-empty-string)

(s/def ::register
  (s/keys :req-un [::fullname ::password ::email]
          :opt-un [::invitation-token]))

(defn register
  "Create a register event instance."
  [data]
  (s/assert ::register data)
  (ptk/reify ::register
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/mutation :register-profile data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Update Profile

(defn update-profile
  [data]
  (us/assert ::profile data)
  (ptk/reify ::update-profile
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [mdata      (meta data)
            on-success (:on-success mdata identity)
            on-error   (:on-error mdata #(rx/throw %))]
        (->> (rp/mutation :update-profile data)
             (rx/catch on-error)
             (rx/mapcat
              (fn [_]
                (rx/merge
                 (->> stream
                      (rx/filter (ptk/type? ::profile-fetched))
                      (rx/take 1)
                      (rx/tap on-success)
                      (rx/ignore))
                 (rx/of (profile-fetched data))))))))))


;; --- Request Email Change

(defn request-email-change
  [{:keys [email] :as data}]
  (us/assert ::us/email email)
  (ptk/reify ::request-email-change
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/mutation :request-email-change data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Cancel Email Change

(def cancel-email-change
  (ptk/reify ::cancel-email-change
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation :cancel-email-change {})
           (rx/map (constantly (fetch-profile)))))))

;; --- Update Password (Form)

(s/def ::update-password
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(defn update-password
  [data]
  (us/verify ::update-password data)
  (ptk/reify ::update-password
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)
            params {:old-password (:password-old data)
                    :password (:password-1 data)}]
        (->> (rp/mutation :update-profile-password params)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty)))
             (rx/ignore))))))


(defn mark-onboarding-as-viewed
  ([] (mark-onboarding-as-viewed nil))
  ([{:keys [version]}]
   (ptk/reify ::mark-onboarding-as-viewed
     ptk/WatchEvent
     (watch [_ state _]
       (let [version (or version (:main @cf/version))
             props   (-> (get-in state [:profile :props])
                         (assoc :onboarding-viewed true)
                         (assoc :release-notes-viewed version))]
         (->> (rp/mutation :update-profile-props {:props props})
              (rx/map (constantly (fetch-profile)))))))))

;; --- Update Photo

(defn update-photo
  [file]
  (us/verify ::di/blob file)
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
             (rx/mapcat #(rp/mutation :update-profile-photo %))
             (rx/do on-success)
             (rx/map (constantly (fetch-profile)))
             (rx/catch on-error))))))


(defn fetch-users
  [{:keys [team-id] :as params}]
  (us/assert ::us/uuid team-id)
  (letfn [(fetched [users state]
            (->> users
                 (d/index-by :id)
                 (assoc state :users)))]
    (ptk/reify ::fetch-team-users
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/query :team-users {:team-id team-id})
             (rx/map #(partial fetched %)))))))

;; --- EVENT: request-account-deletion

(defn request-account-deletion
  [params]
  (ptk/reify ::request-account-deletion
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta params)]
        (->> (rp/mutation :delete-profile {})
             (rx/tap on-success)
             (rx/delay-at-least 300)
             (rx/catch (constantly (rx/of 1)))
             (rx/map logged-out)
             (rx/catch on-error))))))

;; --- EVENT: request-profile-recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(defn request-profile-recovery
  [data]
  (us/verify ::request-profile-recovery data)
  (ptk/reify ::request-profile-recovery
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)]

        (->> (rp/mutation :request-profile-recovery data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: recover-profile (Password)

(s/def ::token string?)
(s/def ::recover-profile
  (s/keys :req-un [::password ::token]))

(defn recover-profile
  [data]
  (us/verify ::recover-profile data)
  (ptk/reify ::recover-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-error on-success]
             :or {on-error rx/throw
                  on-success identity}} (meta data)]
        (->> (rp/mutation :recover-profile data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- EVENT: crete-demo-profile

(defn create-demo-profile
  []
  (ptk/reify ::create-demo-profile
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/mutation :create-demo-profile {})
           (rx/map login)))))


