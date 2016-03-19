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
    (assoc! local-storage ::auth data)))

;; --- Login

(defrecord Login [username password]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-error [err]
              (uum/error (tr "errors.auth"))
              (rx/empty))
            (on-success [{value :payload}]
              (->LoggedIn value))]
      (->> (rp/do :login (merge (into {} this) {:scope "webapp"}))
           (rx/map on-success)
           (rx/catch on-error)))))

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
    (rx/of (r/navigate :auth/login))))

(defn logout
  []
  (->Logout))
