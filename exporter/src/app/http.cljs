;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http
  (:require
   [app.config :as cf]
   [app.http.export :refer [export-handler]]
   [app.http.impl :as impl]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]
   [reitit.core :as r]))

(def routes
  [["/export" {:handler export-handler}]])

(def instance (atom nil))

(defn init
  []
  (let [router  (r/router routes)
        handler (impl/handler router)
        server  (impl/server handler)
        port    (cf/get :http-server-port 6061)]
    (.listen server port)
    (log/info :msg "starting http server" :port port)
    (reset! instance server)))

(defn stop
  []
  (if-let [server @instance]
    (p/create (fn [resolve]
                (.close server (fn []
                                 (log/info :msg "shutdown http server")
                                 (resolve)))))
    (p/resolved nil)))
