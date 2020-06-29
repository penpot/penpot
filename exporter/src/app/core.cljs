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
  (p/let [browser (bwr/start!)
          server  (http/start! {:browser browser})]
    (reset! state {:http server
                   :browser browser})))

(def main start)

(defn stop
  [done]
  ;; an empty line for visual feedback of restart
  (js/console.log "")

  (log/info :msg "stoping")
  (p/do!
   (when-let [instance (:browser @state)]
     (bwr/stop! instance))
   (when-let [instance (:http @state)]
     (http/stop! instance))
   (done)))
