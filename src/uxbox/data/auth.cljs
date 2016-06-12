;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.auth
  (:require [beicon.core :as rx]
            [promesa.core :as p]
            [uxbox.repo :as rp]
            [uxbox.rstore :as rs]
            [uxbox.router :as rt]
            [uxbox.state :as st]
            [uxbox.schema :as us]
            [uxbox.locales :refer (tr)]
            [uxbox.data.projects :as udp]
            [uxbox.data.users :as udu]
            [uxbox.data.messages :as udm]
            [uxbox.data.forms :as udf]
            [uxbox.util.storage :refer (storage)]))

;; --- Logged In

(defrecord LoggedIn [data]
  rs/UpdateEvent
  (-apply-update [this state]
    (assoc state :auth data))

  rs/WatchEvent
  (-apply-watch [this state s]
    (rx/of (udu/fetch-profile)
           (rt/navigate :dashboard/projects)))

  rs/EffectEvent
  (-apply-effect [this state]
    (swap! storage assoc :uxbox/auth data)))

(defn logged-in?
  [v]
  (instance? LoggedIn v))

(defn logged-in
  [data]
  (LoggedIn. data))

;; --- Login

(defrecord Login [username password]
  rs/UpdateEvent
  (-apply-update [_ state]
    (merge state (dissoc (st/initial-state) :route)))

  rs/WatchEvent
  (-apply-watch [this state s]
    (let [params {:username username
                  :password password
                  :scope "webapp"}
          on-error #(udm/error (tr "errors.auth.unauthorized"))]
      (->> (rp/req :fetch/token params)
           (rx/map :payload)
           (rx/map logged-in)
           (rx/catch rp/client-error? on-error)))))

(defn login
  [params]
  (map->Login params))

;; --- Logout

(defrecord Logout []
  rs/UpdateEvent
  (-apply-update [_ state]
    (swap! storage dissoc :uxbox/auth)
    (merge state (dissoc (st/initial-state) :route)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (rt/navigate :auth/login))))

(defn logout
  []
  (->Logout))

;; --- Register

(defrecord Register [data]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (->> (:payload payload)
                   (udf/assign-errors :register)
                   (rx/of)))]
      (rx/merge
       (->> (rp/req :auth/register data)
            (rx/map :payload)
            (rx/map (constantly ::registered))
            (rx/catch rp/client-error? on-error))
       (->> stream
            (rx/filter #(= % ::registered))
            (rx/take 1)
            (rx/map #(login data)))
       (->> stream
            (rx/filter logged-in?)
            (rx/take 1)
            (rx/map #(udf/clean :register)))))))

(def register-schema
  {:username [us/required us/string]
   :fullname [us/required us/string]
   :email [us/required us/email]
   :password [us/required us/string]})

(defn register
  "Create a register event instance."
  [data]
  (let [[errors data] (us/validate data register-schema)]
    (if errors
      (udf/assign-errors :register errors)
      (Register. data))))

;; --- Recovery Request

(defrecord RecoveryRequest [data]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (println "on-error" payload)
              (->> (:payload payload)
                   (udf/assign-errors :recovery-request)
                   (rx/of)))]
      (rx/merge
       (->> (rp/req :auth/recovery-request data)
            (rx/map (constantly ::recovery-requested))
            (rx/catch rp/client-error? on-error))
       (->> stream
            (rx/filter #(= % ::recovery-requested))
            (rx/take 1)
            (rx/do #(udm/info! (tr "auth.message.recovery-token-sent")))
            (rx/map #(udf/clean :recovery-request)))))))

(defn recovery-request
  [data]
  (RecoveryRequest. data))

;; --- Check Recovery Token

(defrecord ValidateRecoveryToken [token]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (rx/of
               (rt/navigate :auth/login)
               (udm/show-error (tr "errors.auth.invalid-recovery-token"))))]
      (->> (rp/req :auth/validate-recovery-token token)
           (rx/ignore)
           (rx/catch rp/client-error? on-error)))))

(defn validate-recovery-token
  [data]
  (ValidateRecoveryToken. data))

;; --- Recovery (Password)

(defrecord Recovery [token password]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (letfn [(on-error [{payload :payload}]
              (udm/error (tr "errors.auth.invalid-recovery-token")))
            (on-success [{payload :payload}]
              (rx/of
               (rt/navigate :auth/login)
               (udm/show-info (tr "auth.message.password-recovered"))))]
      (->> (rp/req :auth/recovery {:token token :password password})
           (rx/mapcat on-success)
           (rx/catch rp/client-error? on-error)))))


(defn recovery
  [{:keys [token password]}]
  (Recovery. token password))

