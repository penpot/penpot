;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.auth
  (:require [cljs.spec.alpha :as s]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.repo :as rp]
            [uxbox.main.state :refer [initial-state]]
            [uxbox.main.data.projects :as udp]
            [uxbox.main.data.users :as udu]
            [uxbox.util.messages :as uum]
            [uxbox.util.router :as rt]
            [uxbox.util.spec :as us]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.storage :refer [storage]]))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::fullname string?)
(s/def ::email us/email?)
(s/def ::token string?)

;; --- Logged In

(defrecord LoggedIn [data]
  ptk/UpdateEvent
  (update [this state]
    (assoc state :auth data))

  ptk/WatchEvent
  (watch [this state s]
    (swap! storage assoc :auth data)
    (rx/of (udu/fetch-profile)
           (rt/navigate :dashboard/projects))))

(defn logged-in?
  [v]
  (instance? LoggedIn v))

(defn logged-in
  [data]
  (LoggedIn. data))

;; --- Login

(defrecord Login [username password]
  ptk/UpdateEvent
  (update [_ state]
    (merge state (dissoc (initial-state) :route)))

  ptk/WatchEvent
  (watch [this state s]
    (let [params {:username username
                  :password password
                  :scope "webapp"}
          on-error #(rx/of (uum/error (tr "errors.auth.unauthorized")))]
      (->> (rp/req :fetch/token params)
           (rx/map :payload)
           (rx/map logged-in)
           (rx/catch rp/client-error? on-error)))))

(s/def ::login-event
  (s/keys :req-un [::username ::password]))

(defn login
  [params]
  {:pre [(us/valid? ::login-event params)]}
  (map->Login params))

;; --- Logout

(defrecord Logout []
  ptk/UpdateEvent
  (update [_ state]
    (swap! storage dissoc :auth)
    (merge state (dissoc (initial-state) :route)))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (rt/navigate :auth/login))))

(defn logout
  []
  (->Logout))

;; --- Register

;; TODO: clean form on success

(defrecord Register [data on-error]
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
            (rx/map #(login data)))))))

(s/def ::register-event
  (s/keys :req-un [::fullname ::username ::email ::password]))

(defn register
  "Create a register event instance."
  [data on-error]
  {:pre [(us/valid? ::register-event data)
         (fn? on-error)]}
  (Register. data on-error))

;; --- Recovery Request

(defrecord RecoveryRequest [data]
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
            (rx/map #(uum/info (tr "auth.message.recovery-token-sent"))))))))

(s/def ::recovery-request-event
  (s/keys :req-un [::username]))

(defn recovery-request
  [data]
  {:pre [(us/valid? ::recovery-request-event data)]}
  (RecoveryRequest. data))

;; --- Check Recovery Token

(defrecord ValidateRecoveryToken [token]
  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (rx/of
               (rt/navigate :auth/login)
               (uum/error (tr "errors.auth.invalid-recovery-token"))))]
      (->> (rp/req :auth/validate-recovery-token token)
           (rx/ignore)
           (rx/catch rp/client-error? on-error)))))

(defn validate-recovery-token
  [token]
  {:pre [(string? token)]}
  (ValidateRecoveryToken. token))

;; --- Recovery (Password)

(defrecord Recovery [token password]
  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (rx/of (uum/error (tr "errors.auth.invalid-recovery-token"))))
            (on-success [{payload :payload}]
              (rx/of
               (rt/navigate :auth/login)
               (uum/info (tr "auth.message.password-recovered"))))]
      (->> (rp/req :auth/recovery {:token token :password password})
           (rx/mapcat on-success)
           (rx/catch rp/client-error? on-error)))))

(s/def ::recovery-event
  (s/keys :req-un [::username ::token]))

(defn recovery
  [{:keys [token password] :as data}]
  {:pre [(us/valid? ::recovery-event data)]}
  (Recovery. token password))
