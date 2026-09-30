;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.instance
  "Identity and liveness of this exporter process.

  Every process of a distributed deployment registers itself in a shared set
  and keeps a heartbeat key alive. A heartbeat that expired means the process
  is gone, and whatever it had claimed from the queue can be handed to
  another one (see `app.jobs.queue/reap!`)."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.redis :as redis]
   [promesa.core :as p]))

(defonce id (str (uuid/next)))

(defonce ^:private timer (atom nil))

(defn registry-key
  []
  (redis/->key "instances"))

(defn heartbeat-key
  [instance-id]
  (redis/->key "instance." instance-id))

(defn- beat!
  []
  (->> (p/do
         (redis/set-ex! (heartbeat-key id) (name (cf/role)) (cf/get :exporter-heartbeat-ttl))
         (redis/sadd! (registry-key) id))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to renew heartbeat" :instance id :cause cause)
                 (p/resolved nil)))))

(defn alive?
  [instance-id]
  (redis/key-exists? (heartbeat-key instance-id)))

(defn members
  "Every instance registered, alive or not."
  []
  (redis/smembers (registry-key)))

(defn forget!
  [instance-id]
  (redis/srem! (registry-key) instance-id))

(defn describe
  "Every registered instance with its role, nil once its heartbeat expired."
  []
  (->> (members)
       (p/mcat (fn [ids]
                 (->> ids
                      (map (fn [instance-id]
                             (->> (redis/get-key (heartbeat-key instance-id))
                                  (p/fmap (fn [role]
                                            {:id instance-id
                                             :role role
                                             :alive (some? role)})))))
                      (p/all))))))

(defn stop
  []
  (when-let [t @timer]
    (js/clearInterval t))
  (reset! timer nil))

(defn init
  []
  (stop)
  (l/info :hint "instance registered" :instance id :role (name (cf/role)))
  (reset! timer (js/setInterval beat! (* 1000 (cf/get :exporter-heartbeat-interval))))
  (beat!))
