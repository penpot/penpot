;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http
  (:require
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.core :refer [system]]
   [uxbox.http.errors :as errors]
   [uxbox.http.interceptors :as interceptors]
   [uxbox.http.session :as session]
   [uxbox.http.handlers :as handlers]
   [uxbox.http.debug :as debug]
   [uxbox.http.ws :as ws]
   [vertx.core :as vc]
   [vertx.http :as vh]
   [vertx.web :as vw]
   [vertx.web.interceptors :as vxi]))

(declare login-handler)
(declare logout-handler)
(declare register-handler)
(declare mutation-handler)
(declare query-handler)
(declare echo-handler)

(defn- on-start
  [ctx]
  (let [cors-opts {:origin (:http-server-cors cfg/config "http://localhost:3449")
                   :max-age 3600
                   :allow-credentials true
                   :allow-methods #{:post :get :patch :head :options :put}
                   :allow-headers #{:x-requested-with :content-type :cookie}}

        interceptors [(vxi/cookies)
                      (vxi/params)
                      (vxi/cors cors-opts)
                      interceptors/parse-request-body
                      interceptors/format-response-body
                      (vxi/errors errors/handle)]

        routes [["/sub/:page-id" {:interceptors [(vxi/cookies)
                                                 (vxi/cors cors-opts)
                                                 (session/auth)]
                                  :get ws/handler}]

                ["/api" {:interceptors interceptors}
                 ["/echo" {:all handlers/echo-handler}]
                 ["/login" {:post handlers/login-handler}]
                 ["/logout" {:post handlers/logout-handler}]
                 ["/register" {:post handlers/register-handler}]
                 ["/debug"
                  ["/emails" {:get debug/emails-list}]
                  ["/emails/:id" {:get debug/email}]]
                 ["/w" {:interceptors [(session/auth)]}
                  ["/mutation/:type" {:interceptors [(vxi/uploads)]
                                      :post handlers/mutation-handler}]
                  ["/query/:type" {:get handlers/query-handler}]]]]

        handler (vw/handler ctx
                            (vw/assets "/media/*" {:root "resources/public/media/"})
                            (vw/assets "/static/*" {:root "resources/public/static"})
                            (vw/router routes))]

    (log/info "Starting http server on" (:http-server-port cfg/config) "port.")
    (vh/server ctx {:handler handler
                    :port (:http-server-port cfg/config)})))

(defstate server
  :start (let [factory (vc/verticle {:on-start on-start})]
           @(vc/deploy! system factory {:instances 4})))
