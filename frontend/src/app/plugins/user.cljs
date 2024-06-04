;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.user
  (:require
   [app.common.record :as crc]
   [app.config :as cfg]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(deftype CurrentUserProxy [$session])
(deftype ActiveUserProxy [$session])

(defn add-user-properties
  [user-proxy]
  (let [session-id (obj/get user-proxy "$session")]
    (crc/add-properties!
     user-proxy
     {:name "id"
      :get (fn [_] (-> (u/locate-profile session-id) :id str))}

     {:name "name"
      :get (fn [_] (-> (u/locate-profile session-id) :fullname))}

     {:name "avatarUrl"
      :get (fn [_] (cfg/resolve-profile-photo-url (u/locate-profile session-id)))}

     {:name "color"
      :get (fn [_] (-> (u/locate-presence session-id) :color))}

     {:name "sessionId"
      :get (fn [_] (str session-id))})))

(defn current-user-proxy
  [session-id]
  (-> (CurrentUserProxy. session-id)
      (add-user-properties)))

(defn active-user-proxy
  [session-id]
  (-> (ActiveUserProxy. session-id)
      (add-user-properties)
      (crc/add-properties!
       {:name "position" :get (fn [_] (-> (u/locate-presence session-id) :point u/to-js))}
       {:name "zoom" :get (fn [_] (-> (u/locate-presence session-id) :zoom))})))
