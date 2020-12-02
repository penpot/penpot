;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.redis
  (:refer-clojure :exclude [run!])
  (:require
   [app.config :as cfg]
   [app.util.redis :as redis]
   [mount.core :as mount :refer [defstate]])
  (:import
   java.lang.AutoCloseable))

;; --- Connection Handling & State

(defn- create-client
  [config]
  (let [uri (:redis-uri config "redis://redis/0")]
    (redis/client uri)))

(declare client)

(defstate client
  :start (create-client cfg/config)
  :stop (.close ^AutoCloseable client))

(declare conn)

(defstate conn
  :start (redis/connect client)
  :stop (.close ^AutoCloseable conn))

;; --- API FORWARD

(defn subscribe
  [opts]
  (redis/subscribe client opts))

(defn run!
  [cmd params]
  (redis/run! conn cmd params))

(defn run
  [cmd params]
  (redis/run conn cmd params))
