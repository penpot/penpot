;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.core
  (:require
   ["node:process" :as proc]
   [app.browser :as bwr]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http :as http]
   [app.redis :as redis]
   [promesa.core :as p]))

(enable-console-print!)
(l/setup! {:app :info})

(defn start
  [& _]
  (l/info :msg "initializing"
          :public-uri (str (cf/get :public-uri))
          :version (:full cf/version))
  (p/do!
   (bwr/init)
   (redis/init)
   (http/init)))

(def main start)

(defn stop
  [done]
  ;; an empty line for visual feedback of restart
  (js/console.log "")

  (l/info :msg "stopping")
  (p/do!
   (bwr/stop)
   (redis/stop)
   (http/stop)
   (done)))

(.on proc/default "uncaughtException"
     (fn [cause]
       (js/console.error cause)))

(.on proc/default "SIGTERM" (fn [] (proc/exit 0)))
(.on proc/default "SIGINT" (fn [] (proc/exit 0)))
