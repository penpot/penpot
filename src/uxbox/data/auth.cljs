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
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.locales :refer (tr)]
            [uxbox.data.projects :as udp]
            [uxbox.data.users :as udu]
            [uxbox.data.messages :as udm]
            [uxbox.util.storage :refer (storage)]))

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
    (merge state (dissoc (st/get-initial-state) :route)))

  rs/WatchEvent
  (-apply-watch [this state s]
    (let [params {:username username
                  :password password
                  :scope "webapp"}]
      (->> (rp/req :fetch/token params)
           (rx/map :payload)
           (rx/mapcat #(rx/of (logged-in %)
                              (udp/fetch-projects)
                              (udu/fetch-profile)))
           (rx/catch rp/client-error? #(udm/error (tr "errors.auth.unauthorized")))))))

(defn login
  [params]
  (map->Login params))

;; --- Logout

(defrecord Logout []
  rs/UpdateEvent
  (-apply-update [_ state]
    (merge state (dissoc (st/get-initial-state) :route)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (r/navigate :auth/login)))

  rs/EffectEvent
  (-apply-effect [this state]
    (swap! storage dissoc :uxbox/auth)))

(defn logout
  []
  (->Logout))
