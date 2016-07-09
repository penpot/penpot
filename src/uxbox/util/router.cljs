;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.router
  (:require [bidi.router]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [lentes.core :as l]
            [beicon.core :as rx]
            [uxbox.util.rstore :as rs]))

(enable-console-print!)

(defonce +router+ nil)
(defonce +routes+ nil)

;; --- Update Location (Event)

(defrecord UpdateLocation [id params]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [route (merge {:id id}
                       (when params
                         {:params params}))]
      (assoc state :route route))))

(defn update-location?
  [v]
  (instance? UpdateLocation v))

(defn update-location
  [{:keys [handler route-params] :as params}]
  (UpdateLocation. handler route-params))

;; --- Navigate (Event)

(defrecord Navigate [id params]
  rs/EffectEvent
  (-apply-effect [_ state]
    (let [loc (merge {:handler id}
                     (when params
                       {:route-params params}))]
      (bidi.router/set-location! +router+ loc))))

(defn navigate
  ([id] (navigate id nil))
  ([id params]
   {:pre [(keyword? id)]}
   (Navigate. id params)))

;; --- Public Api

(defn init
  ([routes]
   (init routes nil))
  ([routes {:keys [default] :or {default :auth/login}}]
   (let [opts {:on-navigate #(rs/emit! (update-location %))
               :default-location {:handler default}}
         router (bidi.router/start-router! routes opts)]
     (set! +routes+ routes)
     (set! +router+ router))))

(defn go
  "Redirect the user to other url."
  ([id] (go id nil))
  ([id params]
   (rs/emit! (navigate id params))))

(defn route-for
  "Given a location handler and optional parameter map, return the URI
  for such handler and parameters."
  ([id]
   (bidi/path-for +routes+ id))
  ([id params]
   (apply bidi/path-for +routes+ id (into [] (mapcat (fn [[k v]] [k v])) params))))
