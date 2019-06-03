;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.users
  (:require [cljs.spec.alpha :as s]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.repo :as rp]
            [uxbox.util.spec :as us]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.messages :as uum]))

;; --- Profile Fetched

(deftype ProfileFetched [data]
  ptk/UpdateEvent
  (update [this state]
    (assoc state :profile data)))

(defn profile-fetched
  [data]
  (ProfileFetched. data))

;; --- Fetch Profile

(deftype FetchProfile []
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/profile)
         (rx/map :payload)
         (rx/map profile-fetched))))

(defn fetch-profile
  []
  (FetchProfile.))

;; --- Profile Updated

(deftype ProfileUpdated [data]
  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (profile-fetched data)
           (uum/info (tr "settings.profile.profile-saved")))))

(defn profile-updated
  [data]
  (ProfileUpdated. data))

;; --- Update Profile

(deftype UpdateProfile [data on-success on-error]
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

(s/def ::fullname string?)
(s/def ::email us/email?)
(s/def ::username string?)
(s/def ::theme string?)

(s/def ::update-profile
  (s/keys :req-un [::fullname
                   ::email
                   ::username
                   ::theme]))

(defn update-profile
  [data on-success on-error]
  {:pre [(us/valid? ::update-profile data)
         (fn? on-error)
         (fn? on-success)]}
  (UpdateProfile. data on-success on-error))

;; --- Update Password (Form)

(deftype UpdatePassword [data on-success on-error]
  ptk/WatchEvent
  (watch [_ state s]
    (let [params {:old-password (:password-old data)
                  :password (:password-1 data)}]
      (->> (rp/req :update/profile-password params)
           (rx/catch rp/client-error? (fn [e]
                                        (on-error (:payload e))
                                        (rx/empty)))
           (rx/do on-success)
           (rx/ignore)))))

(s/def ::password-1 string?)
(s/def ::password-2 string?)
(s/def ::password-old string?)

(s/def ::update-password
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(defn update-password
  [data & {:keys [on-success on-error]}]
  {:pre [(us/valid? ::update-password data)
         (fn? on-success)
         (fn? on-error)]}
  (UpdatePassword. data on-success on-error))

;; --- Update Photo

(deftype UpdatePhoto [file done]
  ptk/WatchEvent
  (watch [_ state stream]
    (->> (rp/req :update/profile-photo {:file file})
         (rx/do done)
         (rx/map fetch-profile))))

(defn update-photo
  ([file] (update-photo file (constantly nil)))
  ([file done]
   {:pre [(us/file? file)
          (fn? done)]}
   (UpdatePhoto. file done)))
