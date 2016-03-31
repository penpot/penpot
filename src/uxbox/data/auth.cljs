;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.auth
  (:require [hodgepodge.core :refer [local-storage]]
            [beicon.core :as rx]
            [promesa.core :as p]
            [uxbox.repo :as rp]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
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
    (println "FetchProfile")
    (letfn [(on-error [err]
              (uum/error (tr "errors.profile-fetch"))
              (rx/empty))]
      (->> (rp/do :fetch/profile)
           (rx/catch on-error)
           (rx/map :payload)
           (rx/map profile-fetched)))))

(defn fetch-profile
  []
  (FetchProfile.))

;; --- Logged In

(defrecord LoggedIn [data]
  rs/UpdateEvent
  (-apply-update [this state]
    (assoc state :auth data))

  rs/WatchEvent
  (-apply-watch [this state s]
    (rx/of (r/navigate :dashboard/projects)))

  rs/EffectEvent
  (-apply-effect [this state]
    (assoc! local-storage :uxbox/auth data)))

(defn logged-in?
  [v]
  (instance? LoggedIn v))

(defn logged-in
  [data]
  (LoggedIn. data))

;; --- Login

(defrecord Login [username password]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-error [err]
              (uum/error (tr "errors.auth"))
              (rx/empty))]
      (let [params {:username username
                    :password password
                    :scope "webapp"}]
        (->> (rp/do :fetch/token params)
             (rx/catch on-error)
             (rx/map :payload)
             (rx/mapcat #(rx/of (logged-in %)
                                (fetch-profile))))))))


(def ^:const ^:private +login-schema+
  {:username [sc/required sc/string]
   :password [sc/required sc/string]})

(defn login
  [params]
  (sc/validate! +login-schema+ params)
  (map->Login params))

;; --- Logout

(defrecord Logout []
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc state :auth nil))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (r/navigate :auth/login)))

  rs/EffectEvent
  (-apply-effect [this state]
    (dissoc! local-storage :uxbox/auth)))

(defn logout
  []
  (->Logout))
