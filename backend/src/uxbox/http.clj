;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.http
  (:require
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [reitit.ring :as rring]
   [ring.adapter.jetty9 :as jetty]
   [uxbox.config :as cfg]
   [uxbox.http.auth :as auth]
   [uxbox.http.auth.google :as google]
   [uxbox.http.auth.ldap :as ldap]
   [uxbox.http.debug :as debug]
   [uxbox.http.errors :as errors]
   [uxbox.http.handlers :as handlers]
   [uxbox.http.middleware :as middleware]
   [uxbox.http.session :as session]
   [uxbox.http.ws :as ws]
   [uxbox.metrics :as mtx]
   [uxbox.services.notifications :as usn]))

(defn- create-router
  []
  (rring/router
   [["/metrics" {:get mtx/dump}]
    ["/api" {:middleware [[middleware/format-response-body]
                          [middleware/errors errors/handle]
                          [middleware/parse-request-body]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/cookies]]}

     ["/oauth"
      ["/google" {:post google/auth}]
      ["/google/callback" {:get google/callback}]]

     ["/echo" {:get handlers/echo-handler
               :post handlers/echo-handler}]

     ["/login" {:handler auth/login-handler
                :method :post}]
     ["/logout" {:handler auth/logout-handler
                 :method :post}]
     ["/login-ldap" {:handler ldap/auth
                     :method :post}]

     ["/w" {:middleware [session/middleware]}
      ["/query/:type" {:get handlers/query-handler}]
      ["/mutation/:type" {:post handlers/mutation-handler}]]]]))

(defn start-server
  []
  (let [wsockets {"/ws/notifications" ws/handler}
        options  {:port (:http-server-port cfg/config)
                  :h2c? true
                  :join? false
                  :allow-null-path-info true
                  :websockets wsockets}
        handler  (rring/ring-handler
                  (create-router)
                  (constantly {:status 404, :body ""})
                  {:middleware [[middleware/development-resources]
                                [middleware/development-cors]
                                [middleware/metrics]]})]
    (log/infof "Http server listening on http://localhost:%s/"
               (:http-server-port cfg/config))
    (jetty/run-jetty handler options)))

(defstate server
  :start (start-server)
  :stop (.stop server))
