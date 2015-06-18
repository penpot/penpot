(ns uxbox.main
  (:require [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [uxbox.router]
            [uxbox.config])
  (:gen-class))

(defn system
  []
  (component/system-map
   :config (uxbox.config/component (:config env))
   :web (uxbox.router/component)))

(defn -main
  [& args]
  (component/start (system)))
