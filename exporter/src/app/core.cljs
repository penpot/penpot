;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.core
  (:require
   ["node:process" :as proc]
   [app.browser :as bwr]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http :as http]
   [app.redis :as redis]
   [app.wasm :as wasm]
   [promesa.core :as p]))

(enable-console-print!)
(l/setup! {:app :info})

(defn start
  [& _]
  (l/info :msg "initializing"
          :public-uri (str (cf/get :public-uri))
          :internal-uri (str (cf/get-internal-uri))
          :version (:full cf/version))
  (when (contains? cf/flags :wasm-export)
    (l/warn :msg "headless wasm export enabled (experimental)"
            :hint (str "renders run in-process on a single shared wasm module, "
                       "one at a time; not recommended for busy instances")
            :wasm-dir wasm/artifact-dir
            :image-cache-mb wasm/image-cache-mb))
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
