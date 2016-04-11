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

;; --- Update Password

(defrecord UpdatePassword [old-password password]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-error [err]
              (uum/error (tr "errors.profile.update-password"))
              (rx/empty))]
      (->> (rp/req :update/password {:old-password old-password :password password})
           (rx/catch on-error)))))

(defn update-password
  [{:keys [old-password password]}]
  (UpdatePassword. old-password password))
