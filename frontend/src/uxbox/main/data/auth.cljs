;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.auth
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :refer [initial-state]]
   [uxbox.main.data.users :as du]
   [uxbox.util.messages :as um]
   [uxbox.util.router :as rt]
   [uxbox.util.spec :as us]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.storage :refer [storage]]))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::fullname string?)
(s/def ::email ::us/email)

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
  (s/keys :req-un [::username ::password]))

(defn login
  [{:keys [username password] :as data}]
  (s/assert ::login-params data)
  (ptk/reify ::login
    ptk/UpdateEvent
    (update [_ state]
      (merge state (dissoc initial-state :route :router)))

    ptk/WatchEvent
    (watch [this state s]
      (let [params {:username username
                    :password password
                    :scope "webapp"}
            on-error #(rx/of (um/error (tr "errors.auth.unauthorized")))]
        (->> (rp/req :auth/login params)
             (rx/map :payload)
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
      (->> (rp/req :auth/logout)
           (rx/ignore)))

    ptk/EffectEvent
    (effect [_ state s]
      (reset! storage {})
      (i18n/set-default-locale!))))

(def logout
  (ptk/reify ::logout
    ptk/WatchEvent
    (watch [_ state s]
      (rx/of (rt/nav :auth/login)
             clear-user-data))))

;; --- Register

(s/def ::register-params
  (s/keys :req-un [::fullname
                   ::username
                   ::password
                   ::email]))

(defn register
  "Create a register event instance."
  [data on-error]
  (s/assert ::register-params data)
  (s/assert ::us/fn on-error)
  (ptk/reify ::register
    ptk/WatchEvent
    (watch [_ state stream]
      (letfn [(handle-error [{payload :payload}]
                (on-error payload)
                (rx/empty))]
        (rx/merge
         (->> (rp/req :auth/register data)
              (rx/map :payload)
              (rx/map (constantly ::registered))
              (rx/catch rp/client-error? handle-error))
         (->> stream
              (rx/filter #(= % ::registered))
              (rx/take 1)
              (rx/map #(login data))))))))

;; --- Recovery Request

(s/def ::recovery-request-params
  (s/keys :req-un [::username]))

(defn recovery-request
  [data]
  (s/assert ::recovery-request-params data)
  (ptk/reify ::recover-request
    ptk/WatchEvent
    (watch [_ state stream]
      (letfn [(on-error [{payload :payload}]
                (println "on-error" payload)
                (rx/empty))]
        (rx/merge
         (->> (rp/req :auth/recovery-request data)
              (rx/map (constantly ::recovery-requested))
              (rx/catch rp/client-error? on-error))
         (->> stream
              (rx/filter #(= % ::recovery-requested))
              (rx/take 1)
              ;; TODO: this should be moved to the UI part
              (rx/map #(um/info (tr "auth.message.recovery-token-sent")))))))))

;; --- Check Recovery Token

(defrecord ValidateRecoveryToken [token]
  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (rx/of
               (rt/navigate :auth/login)
               (um/error (tr "errors.auth.invalid-recovery-token"))))]
      (->> (rp/req :auth/validate-recovery-token token)
           (rx/ignore)
           (rx/catch rp/client-error? on-error)))))

(defn validate-recovery-token
  [token]
  {:pre [(string? token)]}
  (ValidateRecoveryToken. token))

;; --- Recovery (Password)

(s/def ::token string?)
(s/def ::recovery-params
  (s/keys :req-un [::username ::token]))

(defn recovery
  [{:keys [token password] :as data}]
  (s/assert ::recovery-params data)
  (ptk/reify ::recovery
    ptk/WatchEvent
    (watch [_ state stream]
      (letfn [(on-error [{payload :payload}]
                (rx/of (um/error (tr "errors.auth.invalid-recovery-token"))))
              (on-success [{payload :payload}]
                (rx/of
                 (rt/navigate :auth/login)
                 (um/info (tr "auth.message.password-recovered"))))]
        (->> (rp/req :auth/recovery {:token token :password password})
             (rx/mapcat on-success)
             (rx/catch rp/client-error? on-error))))))
