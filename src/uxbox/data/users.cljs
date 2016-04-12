;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.data.users
  (:require [beicon.core :as rx]
            [promesa.core :as p]
            [uxbox.repo :as rp]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.locales :refer (tr)]
            [uxbox.data.forms :as forms]
            [uxbox.ui.messages :as uum]))

;; --- Profile Fetched

(defrecord ProfileFetched [data]
  rs/UpdateEvent
  (-apply-update [this state]
    (assoc state :profile data)))

(defn profile-fetched
  [data]
  (ProfileFetched. data))

;; --- Fetch Profile

(defrecord FetchProfile []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-error [err]
              (uum/error (tr "errors.profile-fetch"))
              (rx/empty))]
      (->> (rp/req :fetch/profile)
           (rx/catch on-error)
           (rx/map :payload)
           (rx/map profile-fetched)))))

(defn fetch-profile
  []
  (FetchProfile.))

;; --- Update Profile

(defrecord UpdateProfile [data]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-error [err]
              (uum/error (tr "errors.update-profile"))
              (rx/empty))]
      (->> (rp/req :update/profile data)
           (rx/catch on-error)
           (rx/map :payload)
           (rx/map profile-fetched)))))

(defn update-profile
  [data]
  (UpdateProfile. data))

;; --- Password Updated

(defrecord PasswordUpdated []
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:forms :profile/password] {}))

  rs/EffectEvent
  (-apply-effect [_ state]
    (uum/info (tr "profile.password-saved"))))

;; --- Update Password

(defrecord UpdatePassword [data]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-error [err]
              (uum/error (tr "errors.profile.update-password") {:timeout 3000})
              (rx/empty))]
      (let [params {:old-password (:old-password data)
                    :password (:password-1 data)}]
        (->> (rp/req :update/password params)
             (rx/map #(->PasswordUpdated))
             (rx/catch on-error))))))

(def update-password-schema
  [[:password-1 sc/required sc/string [sc/min-len 6]]
   [:password-2 sc/required sc/string
    [sc/identical-to :password-1 :message "errors.form.password-not-match"]]
   [:old-password sc/required sc/string]])

(defn update-password
  [data]
  (let [[errors data] (sc/validate data update-password-schema)]
    (if errors
      (forms/assign-errors :profile/password errors)
      (UpdatePassword. data))))
