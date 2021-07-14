;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.srepl
  "Server Repl."
  (:require
   [app.common.spec :as us]
   [app.srepl.main]
   [app.util.logging :as l]
   [clojure.core.server :as ccs]
   [clojure.main :as cm]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(defn- repl-init
  []
  (ccs/repl-init)
  (in-ns 'app.srepl.main))

(defn repl
  []
  (cm/repl
   :init repl-init
   :read ccs/repl-read))

;; --- State initialization

(s/def ::name ::us/not-empty-string)
(s/def ::port int?)
(s/def ::host ::us/not-empty-string)

(defmethod ig/pre-init-spec ::server
  [_]
  (s/keys :opt-un [::port ::host ::name]))

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "main"} cfg))

(defmethod ig/init-key ::server
  [_ {:keys [port host name] :as cfg}]
  (when (and port host name)
    (l/info :msg "initializing server repl" :port port :host host :name name)
    (ccs/start-server {:address host
                       :port port
                       :name name
                       :accept 'app.srepl/repl})
    cfg))

(defmethod ig/halt-key! ::server
  [_ cfg]
  (when cfg
    (ccs/stop-server (:name cfg))))


