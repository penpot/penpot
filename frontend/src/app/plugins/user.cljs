;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.user
  (:require
   [app.common.record :as crc]
   [app.config :as cfg]
   [app.plugins.format :as format]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(deftype CurrentUserProxy [$plugin $session])
(deftype ActiveUserProxy [$plugin $session])

(defn add-user-properties
  [user-proxy]
  (let [plugin-id (obj/get user-proxy "$plugin")
        session-id (obj/get user-proxy "$session")]
    (crc/add-properties!
     user-proxy
     {:name "$plugin" :enumerable false :get (constantly plugin-id)}
     {:name "$session" :enumerable false :get (constantly session-id)}

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

(defn current-user-proxy? [p]
  (instance? CurrentUserProxy p))

(defn current-user-proxy
  [plugin-id session-id]
  (-> (CurrentUserProxy. plugin-id session-id)
      (add-user-properties)))

(defn active-user-proxy? [p]
  (instance? ActiveUserProxy p))

(defn active-user-proxy
  [plugin-id session-id]
  (-> (ActiveUserProxy. plugin-id session-id)
      (add-user-properties)
      (crc/add-properties!
       {:name "position" :get (fn [_] (-> (u/locate-presence session-id) :point format/format-point))}
       {:name "zoom" :get (fn [_] (-> (u/locate-presence session-id) :zoom))})))
