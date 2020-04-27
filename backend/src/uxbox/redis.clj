;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.redis
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.tools.logging :as log]
   [lambdaisland.uri :refer [uri]]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]
   [uxbox.core :refer [system]]
   [uxbox.util.redis :as redis]
   [uxbox.util.data :as data]
   [vertx.util :as vu])
  (:import
   java.lang.AutoCloseable))

;; --- Connection Handling & State

(defn- create-client
  [config]
  (let [uri (:redis-uri config "redis://redis/0")]
    (log/info "creating redis client with" uri)
    (redis/client uri)))

(defstate client
  :start (create-client cfg/config)
  :stop (.close ^AutoCloseable client))

(defstate conn
  :start @(redis/connect client)
  :stop (.close ^AutoCloseable conn))

;; --- API FORWARD

(defn subscribe
  [topic]
  (redis/subscribe client topic))

(defn run!
  [cmd params]
  (let [ctx (vu/get-or-create-context system)]
    (-> (redis/run! conn cmd params)
        (vu/handle-on-context ctx))))

(defn run
  [cmd params]
  (redis/run conn cmd params))
