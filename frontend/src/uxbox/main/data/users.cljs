;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.users
  (:require [cljs.spec :as s]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.util.spec :as us]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.main.repo :as rp]
            [uxbox.main.data.messages :as udm]))

(s/def ::fullname string?)
(s/def ::email us/email?)
(s/def ::username string?)
(s/def ::theme string?)

;; --- Profile Fetched

(defrecord ProfileFetched [data]
  ptk/UpdateEvent
  (update [this state]
    (assoc state :profile data)))

(defn profile-fetched
  [data]
  (ProfileFetched. data))

;; --- Fetch Profile

(defrecord FetchProfile []
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/profile)
         (rx/map :payload)
         (rx/map profile-fetched))))

(defn fetch-profile
  []
  (FetchProfile.))

;; --- Profile Updated

(defrecord ProfileUpdated [data]
  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (profile-fetched data)))

  ptk/EffectEvent
  (effect [_ state stream]
    (udm/info! (tr "settings.profile-saved"))))

(defn profile-updated
  [data]
  (ProfileUpdated. data))

;; --- Update Profile

(defrecord UpdateProfile [data on-success on-error]
  ptk/WatchEvent
  (watch [_ state s]
    (letfn [(handle-error [{payload :payload}]
              (on-error payload)
              (rx/empty))]
      (->> (rp/req :update/profile data)
           (rx/map :payload)
           (rx/do on-success)
           (rx/map profile-updated)
           (rx/catch rp/client-error? handle-error)))))

(s/def ::update-profile-event
  (s/keys :req-un [::fullname ::email ::username ::theme]))

(defn update-profile
  [data on-success on-error]
  {:pre [(us/valid? ::update-profile-event data)
         (fn? on-error)
         (fn? on-success)]}
  (UpdateProfile. data on-success on-error))

;; --- Password Updated

(defrecord PasswordUpdated []
  ptk/EffectEvent
  (effect [_ state stream]
    (udm/info! (tr "settings.password-saved"))))

(defn password-updated
  []
  (PasswordUpdated.))

;; --- Update Password (Form)

(defrecord UpdatePassword [data]
  ptk/WatchEvent
  (watch [_ state s]
    (let [params {:old-password (:old-password data)
                  :password (:password-1 data)}]
        (->> (rp/req :update/profile-password params)
             (rx/map password-updated)))))

(s/def ::password-1 string?)
(s/def ::password-2 string?)
(s/def ::old-password string?)

(s/def ::update-password-event
  (s/keys :req-un [::password-1 ::password-2 ::old-password]))

(defn update-password
  [data]
  {:pre [(us/valid? ::update-password-event data)]}
  (UpdatePassword. data))

;; --- Update Photo

(defrecord UpdatePhoto [file done]
  ptk/WatchEvent
  (watch [_ state stream]
    (->> (rp/req :update/profile-photo {:file file})
         (rx/do done)
         (rx/map fetch-profile))))

(defn update-photo
  ([file] (update-photo file (constantly nil)))
  ([file done]
   {:pre [(us/file? file) (fn? done)]}
   (UpdatePhoto. file done)))
