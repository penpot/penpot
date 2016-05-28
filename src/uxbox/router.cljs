;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.router
  (:require [bidi.router]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [lentes.core :as l]
            [uxbox.rstore :as rs]))

(enable-console-print!)

(defonce +router+ (volatile! nil))

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
    ;; (println "navigate" id params)
    (let [loc (merge {:handler id}
                     (when params
                       {:route-params params}))]
      (bidi.router/set-location! @+router+ loc))))

(defn navigate
  ([id] (navigate id nil))
  ([id params]
   {:pre [(keyword? id)]}
   (Navigate. id params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Router declaration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private page-route
  [[bidi/uuid :project-uuid] "/" [bidi/uuid :page-uuid]])

(def routes
  ["/" [["auth/login" :auth/login]
        ["auth/register" :auth/register]
        ["auth/recovery-request" :auth/recovery-request]

        ["settings/" [["profile" :settings/profile]
                      ["password" :settings/password]
                      ["notifications" :settings/notifications]]]

        ["dashboard/" [["projects" :dashboard/projects]
                       ["elements" :dashboard/elements]
                       ["icons" :dashboard/icons]
                       ["images" :dashboard/images]
                       ["colors" :dashboard/colors]]]
        ["workspace/" [[page-route :workspace/page]]]]])

(defn init
  []
  (let [opts {:on-navigate #(rs/emit! (update-location %))
              :default-location {:handler :auth/login}}
        router (bidi.router/start-router! routes opts)]
    (vreset! +router+ router)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn go
  "Redirect the user to other url."
  ([id] (go id nil))
  ([id params] (rs/emit! (navigate id params))))

(defn route-for
  "Given a location handler and optional parameter map, return the URI
  for such handler and parameters."
  ([id]
   (bidi/path-for routes id))
  ([id params]
   (apply bidi/path-for routes id (into [] (mapcat (fn [[k v]] [k v])) params))))
