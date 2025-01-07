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

(deftype CurrentUserProxy [$plugin])
(deftype ActiveUserProxy [$plugin])
(deftype UserProxy [$plugin])

(defn- add-session-properties
  [user-proxy session-id]
  (crc/add-properties!
   user-proxy
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
    :get (fn [_] (str session-id))}))


(defn current-user-proxy? [p]
  (obj/type-of? p "CurrentUserProxy"))

(defn current-user-proxy
  [plugin-id session-id]
  (-> (obj/reify {:name "CurrentUserProxy"}
        :$plugin
        {:enumerable false :get (fn [] plugin-id)})
      (add-session-properties session-id)))

(defn active-user-proxy? [p]
  (obj/type-of? p "ActiveUserProxy"))

(defn active-user-proxy
  [plugin-id session-id]
  (-> (obj/reify {:name "ActiveUserProxy"}
        :$plugin
        {:enumerable false :get (fn [] plugin-id)}

        :position
        {:get (fn [] (-> (u/locate-presence session-id) :point format/format-point))}

        :zoom
        {:get (fn [] (-> (u/locate-presence session-id) :zoom))})
      (add-session-properties session-id)))

(defn- add-user-properties
  [user-proxy data]
  (crc/add-properties!
   user-proxy
   {:name "id"
    :get (fn [_] (-> data :id str))}

   {:name "name"
    :get (fn [_] (-> data :fullname))}

   {:name "avatarUrl"
    :get (fn [_] (cfg/resolve-profile-photo-url data))}))

(defn user-proxy
  [plugin-id data]
  (-> (obj/reify {:name "UserProxy"}
        :$plugin {:enumerable false :get (fn [] plugin-id)})
      (add-user-properties data)))

(defn user-proxy?
  [p]
  (or (obj/type-of? p "UserProxy")
      (current-user-proxy? p)
      (active-user-proxy? p)))
