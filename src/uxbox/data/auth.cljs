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
            [uxbox.util.time :as time]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +login-schema+
  {:username [sc/required sc/string]
   :password [sc/required sc/string]})

(def ^:const +user-schema+
  {:username [sc/required sc/string]
   :email [sc/required sc/email]
   :photo [sc/required sc/string]
   :fullname [sc/required sc/string]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- login-success
  [{:keys [full photo username email] :as params}]
  (sc/validate! +user-schema+ params)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc state :auth params))

    rs/EffectEvent
    (-apply-effect [_ state]
      (r/go :dashboard/projects))))

(defn login
  [{:keys [username password] :as params}]
  (sc/validate! +login-schema+ params)
  (letfn [(on-error [err]
            (rx/of (login-success {})))
          (on-success [value]
            (rx/of (login-success value)))]
    (reify
      rs/WatchEvent
      (-apply-watch [_ state]
        (->> (rp/do :login params)
             (rx/flat-map on-success)
             (rx/catch on-error))))))

(defn logout
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc state :auth nil))

    rs/WatchEvent
    (-apply-watch [_ state]
      (rx/of (r/navigate :auth/login)))))
