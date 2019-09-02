;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.auth
  (:require
   [struct.alpha :as st]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :refer [initial-state]]
   [uxbox.main.data.users :as du]
   [uxbox.util.messages :as um]
   [uxbox.util.router :as rt]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.storage :refer [storage]]))

;; --- Logged In

;; TODO: add spec

(defn logged-in
  [data]
  (reify
    ptk/EventType
    (type [_] ::logged-in)

    ptk/UpdateEvent
    (update [this state]
      (assoc state :auth data))

    ptk/WatchEvent
    (watch [this state s]
      (swap! storage assoc :auth data)
      (rx/of (du/fetch-profile)
             (rt/navigate :dashboard/projects)))))

(defn logged-in?
  [v]
  (= (ptk/type v) ::logged-in))

;; --- Login

(st/defs ::login
  (st/dict :username ::st/string
          :password ::st/string))

(defn login
  [{:keys [username password] :as data}]
  (assert (st/valid? ::login data))
  (reify
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
  (reify
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
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (rx/of (rt/nav :auth/login)
             clear-user-data))))

;; --- Register

(st/defs ::register
  (st/dict :fullname ::st/string
          :username ::st/string
          :password ::st/string
          :email ::st/email))

(defn register
  "Create a register event instance."
  [data on-error]
  (assert (st/valid? ::register data))
  (assert (fn? on-error))
  (reify
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

(st/defs ::recovery-request
  (st/dict :username ::st/string))

(defn recovery-request
  [data]
  (assert (st/valid? ::recovery-request data))
  (reify
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

(st/defs ::recovery
  (st/dict :username ::st/string
          :token ::st/string))

(defn recovery
  [{:keys [token password] :as data}]
  (assert (st/valid? ::recovery data))
  (reify
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
