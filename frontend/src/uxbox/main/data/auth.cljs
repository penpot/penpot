;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.auth
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :refer [initial-state]]
   [uxbox.main.data.users :as du]
   [uxbox.util.messages :as um]
   [uxbox.util.router :as rt]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.storage :refer [storage]]))

(s/def ::email ::us/email)
(s/def ::password string?)
(s/def ::fullname string?)

;; --- Logged In

(defn logged-in
  [data]
  (ptk/reify ::logged-in
    ptk/UpdateEvent
    (update [this state]
      (assoc state :auth data))

    ptk/WatchEvent
    (watch [this state s]
      (swap! storage assoc :auth data)
      (rx/of du/fetch-profile
             (rt/navigate :dashboard-projects)))))

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
      (let [params {:email email
                    :password password
                    :scope "webapp"}
            on-error #(rx/of (um/error (tr "errors.auth.unauthorized")))]
        (->> (rp/mutation :login params)
             (rx/map logged-in)
             (rx/catch rp/client-error? on-error))))))

;; --- Logout

(def clear-user-data
  (ptk/reify ::clear-user-data
    ptk/UpdateEvent
    (update [_ state]
      (merge state (dissoc initial-state :route :router)))

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
    (watch [_ state s]
      (rx/of (rt/nav :login)
             clear-user-data))))

;; --- Register

(s/def ::register
  (s/keys :req-un [::fullname
                   ::password
                   ::email]))

(defn register
  "Create a register event instance."
  [data on-error]
  (s/assert ::register data)
  (s/assert fn? on-error)
  (ptk/reify ::register
    ptk/WatchEvent
    (watch [_ state stream]
      (letfn [(handle-error [{payload :payload}]
                (on-error payload)
                (rx/empty))]
        (->> (rp/mutation :register-profile data)
             (rx/map (fn [_] (login data)))
             (rx/catch rp/client-error? handle-error))))))

;; --- Recovery Request

(s/def ::recovery-request
  (s/keys :req-un [::email]))

(defn request-profile-recovery
  [data on-success]
  (us/verify ::recovery-request data)
  (us/verify fn? on-success)
  (ptk/reify ::request-profile-recovery
    ptk/WatchEvent
    (watch [_ state stream]
      (letfn [(on-error [{payload :payload}]
                (rx/empty))]
        (->> (rp/mutation :request-profile-recovery data)
             (rx/tap on-success)
             (rx/catch rp/client-error? on-error))))))

;; --- Recovery (Password)

(s/def ::token string?)
(s/def ::on-error fn?)
(s/def ::on-success fn?)

(s/def ::recover-profile
  (s/keys :req-un [::password ::token ::on-error ::on-success]))

(defn recover-profile
  [{:keys [token password on-error on-success] :as data}]
  (us/verify ::recover-profile data)
  (ptk/reify ::recover-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :recover-profile {:token token :password password})
           (rx/tap on-success)
           (rx/catch (fn [err]
                       (on-error)
                       (rx/empty)))))))

;; --- Create Demo Profile

(def create-demo-profile
  (ptk/reify ::create-demo-profile
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation :create-demo-profile {})
           (rx/map login)))))
