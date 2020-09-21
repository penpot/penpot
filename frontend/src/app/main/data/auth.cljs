;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.auth
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.spec :as us]
   [app.main.repo :as rp]
   [app.main.store :refer [initial-state]]
   [app.main.data.users :as du]
   [app.main.data.messages :as dm]
   [app.util.router :as rt]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.storage :refer [storage]]))

(s/def ::email ::us/email)
(s/def ::password string?)
(s/def ::fullname string?)

;; --- Logged In

(defn logged-in
  [data]
  (ptk/reify ::logged-in
    ptk/WatchEvent
    (watch [this state stream]
      (let [team-id (:default-team-id data)]
        (rx/of (du/profile-fetched data)
               (rt/nav :dashboard-team {:team-id team-id}))))))

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
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty)))
             (rx/map logged-in))))))

(defn login-from-token
  [{:keys [profile] :as tdata}]
  (ptk/reify ::login-from-token
    ptk/UpdateEvent
    (update [_ state]
      (merge state (dissoc initial-state :route :router)))

    ptk/WatchEvent
    (watch [this state s]
      (let [team-id (:default-team-id profile)]
        (rx/of (du/profile-fetched profile)
               (rt/nav' :dashboard-team {:team-id team-id}))))))

(defn login-with-ldap
  [{:keys [email password] :as data}]
  (us/verify ::login-params data)
  (ptk/reify ::login-with-ldap
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
          (rx/mapcat #(rp/mutation :login-with-ldap params))
          (rx/tap on-success)
          (rx/catch (fn [err]
                      (on-error err)
                      (rx/empty)))
          (rx/map logged-in))))))

;; --- Logout

(def clear-user-data
  (ptk/reify ::clear-user-data
    ptk/UpdateEvent
    (update [_ state]
      (select-keys state [:route :router :session-id :history]))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/mutation :logout)
           (rx/ignore)))

    ptk/EffectEvent
    (effect [_ state s]
      (reset! storage {})
      (i18n/set-default-locale!))))

(def logout
  (ptk/reify ::logout
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of clear-user-data
             (rt/nav :auth-login)))))

;; --- Register

(s/def ::register
  (s/keys :req-un [::fullname
                   ::password
                   ::email]))

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

(def request-account-deletion
  (letfn [(on-error [{:keys [code] :as error}]
            (if (= :app.services.mutations.profile/owner-teams-with-people code)
              (let [msg (tr "settings.notifications.profile-deletion-not-allowed")]
                (rx/of (dm/error msg)))
              (rx/empty)))]
    (ptk/reify ::request-account-deletion
      ptk/WatchEvent
      (watch [_ state stream]
        (rx/concat
         (->> (rp/mutation :delete-profile {})
              (rx/map #(rt/nav :auth-goodbye))
              (rx/catch on-error)))))))

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
             (rx/catch (fn [err]
                         (on-error err)
                         (rx/empty))))))))


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
