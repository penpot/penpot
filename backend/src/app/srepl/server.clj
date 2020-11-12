(ns app.srepl.server
  "Server Repl."
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.main :as cm]
   [clojure.core.server :as ccs]
   [app.srepl.main]
   [mount.core :as mount :refer [defstate]]))

(defn- repl-init
  []
  (ccs/repl-init)
  (in-ns 'app.srepl.main))

(defn repl
  []
  (cm/repl
   :init repl-init
   :read ccs/repl-read))

(defstate server
  :start (ccs/start-server
          {:address "127.0.0.1"
           :port 6061
           :name "main"
           :accept 'app.srepl.server/repl})
  :stop (ccs/stop-server "main"))



