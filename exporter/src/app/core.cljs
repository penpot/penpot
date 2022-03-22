;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.core
  (:require
   ["process" :as proc]
   [app.browser :as bwr]
   [app.redis :as redis]
   [app.common.logging :as l]
   [app.config]
   [app.http :as http]
   [promesa.core :as p]))

(enable-console-print!)
(l/initialize!)

(defn start
  [& args]
  (l/info :msg "initializing")
  (p/do!
   (bwr/init)
   (redis/init)
   (http/init)))

(def main start)

(defn stop
  [done]
  ;; an empty line for visual feedback of restart
  (js/console.log "")

  (l/info :msg "stoping")
  (p/do!
   (bwr/stop)
   (redis/stop)
   (http/stop)
   (done)))

(proc/on "uncaughtException" (fn [cause]
                               (js/console.error cause)))
