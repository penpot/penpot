;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.core
  (:require
   [lambdaisland.glogi :as log]
   [lambdaisland.glogi.console :as glogi-console]
   [promesa.core :as p]
   [app.http :as http]
   [app.config]
   [app.browser :as bwr]))

(glogi-console/install!)
(enable-console-print!)

(defonce state (atom nil))

(defn start
  [& args]
  (log/info :msg "initializing")
  (p/do!
   (bwr/init)
   (http/init)))

(def main start)

(defn stop
  [done]
  ;; an empty line for visual feedback of restart
  (js/console.log "")

  (log/info :msg "stoping")
  (p/do!
   (bwr/stop)
   (http/stop)
   (done)))
