;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.srepl.server
  "Server Repl."
  (:require
   [app.srepl.main]
   [clojure.core.server :as ccs]
   [clojure.main :as cm]
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
           :port 6062
           :name "main"
           :accept 'app.srepl.server/repl})
  :stop (ccs/stop-server "main"))



