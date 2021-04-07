;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.auth
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :refer [initial-state]]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(s/def ::email ::us/email)
(s/def ::password string?)
(s/def ::fullname string?)

;; --- Current team for a profile

(defn current-team-id
  [profile]
  (let [team-id (:current-team-id storage)]
    (or team-id (:default-team-id profile))))

(defn set-current-team!
  [team-id]
  (swap! storage assoc :current-team-id team-id))

;; --- Logged In

(defn logged-in
  [profile]
  (ptk/reify ::logged-in
    ptk/WatchEvent
    (watch [this state stream]
      (let [team-id (current-team-id profile)
            props   (:props profile)]
        (rx/merge
         (rx/of (du/profile-fetched profile)
                (rt/nav' :dashboard-projects {:team-id team-id}))

         (when-not (:onboarding-viewed props)
           (->> (rx/of (modal/show {:type :onboarding}))
                (rx/delay 1000))))))))

;; --- Login

(s/def ::login-params
  (s/keys :req-un [::email ::password]))

(defn login
  [{:keys [email password] :as data}]
  (us/verify ::login-params data)
  (ptk/reify ::login
    ptk/UpdateEvent
    (update [_ state]
      (merge state (dissoc initial-state :route :router)))

    ptk/WatchEvent
    (watch [this state s]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)
            params {:email email
                    :password password
                    :scope "webapp"}]
        (->> (rx/timer 100)
             (rx/mapcat #(rp/mutation :login params))
             (rx/tap on-success)
             (rx/catch on-error)
             (rx/map logged-in))))))

(defn login-from-token
  [{:keys [profile] :as tdata}]
  (ptk/reify ::login-from-token
    ptk/UpdateEvent
    (update [_ state]
      (merge state (dissoc initial-state :route :router)))

    ptk/WatchEvent
    (watch [this state s]
      (rx/of (logged-in profile)))))

;; --- Logout

(def clear-user-data
  (ptk/reify ::clear-user-data
    ptk/UpdateEvent
    (update [_ state]
      (select-keys state [:route :router :session-id :history]))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :logout)
           (rx/catch (constantly (rx/empty)))
           (rx/ignore)))

    ptk/EffectEvent
    (effect [_ state s]
      (reset! storage {})
      (i18n/reset-locale))))

(defn logout
  []
  (ptk/reify ::logout
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of clear-user-data
             (rt/nav :auth-login)))))

;; --- Register

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
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/mutation :register-profile data)
             (rx/tap on-success)
             (rx/catch on-error))))))


;; --- Request Account Deletion

(defn request-account-deletion
  [params]
  (ptk/reify ::request-account-deletion
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta params)]
        (->> (rp/mutation :delete-profile {})
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Recovery Request

(s/def ::recovery-request
  (s/keys :req-un [::email]))

(defn request-profile-recovery
  [data]
  (us/verify ::recovery-request data)
  (ptk/reify ::request-profile-recovery
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]

        (->> (rp/mutation :request-profile-recovery data)
             (rx/tap on-success)
             (rx/catch on-error))))))

;; --- Recovery (Password)

(s/def ::token string?)
(s/def ::recover-profile
  (s/keys :req-un [::password ::token]))

(defn recover-profile
  [{:keys [token password] :as data}]
  (us/verify ::recover-profile data)
  (ptk/reify ::recover-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [on-error on-success]
             :or {on-error identity
                  on-success identity}} (meta data)]
        (->> (rp/mutation :recover-profile data)
             (rx/tap on-success)
             (rx/catch (fn [err]
                         (on-error)
                         (rx/empty))))))))


;; --- Create Demo Profile

(def create-demo-profile
  (ptk/reify ::create-demo-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :create-demo-profile {})
           (rx/map login)))))
