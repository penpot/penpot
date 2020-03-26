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
   [uxbox.core :refer [system]]
   [uxbox.config :as cfg]
   [uxbox.http.errors :as errors]
   [uxbox.http.middleware :as middleware]
   [uxbox.http.session :as session]
   [uxbox.http.handlers :as handlers]
   [uxbox.http.debug :as debug]
   [uxbox.http.ws :as ws]
   [vertx.core :as vc]
   [vertx.http :as vh]
   [vertx.web :as vw]
   [vertx.web.middleware :as vwm]))

(defn- on-start
  [ctx]
  (let [cors-opts {:origin (:http-server-cors cfg/config "http://localhost:3449")
                   :max-age 3600
                   :allow-credentials true
                   :allow-methods #{:post :get :patch :head :options :put}
                   :allow-headers #{:x-requested-with :content-type :cookie}}

        routes [["/sub/:file-id" {:middleware [[vwm/cookies]
                                               [vwm/cors cors-opts]
                                               [middleware/format-response-body]
                                               [session/auth]]
                                  :handler ws/handler
                                  :method :get}]

                ["/api" {:middleware [[vwm/cookies]
                                      [vwm/params]
                                      [vwm/cors cors-opts]
                                      [middleware/parse-request-body]
                                      [middleware/format-response-body]
                                      [middleware/method-match]
                                      [vwm/errors errors/handle]]}
                 ["/echo" {:handler handlers/echo-handler}]

                 ["/login" {:handler handlers/login-handler
                            :method :post}]
                 ["/logout" {:handler handlers/logout-handler
                             :method :post}]
                 ["/w" {:middleware [session/auth]}
                  ["/mutation/:type" {:middleware [vwm/uploads]
                                      :handler handlers/mutation-handler
                                      :method :post}]
                  ["/query/:type" {:handler handlers/query-handler
                                   :method :get}]]]]

        handler (vw/handler ctx
                            (vw/assets "/media/*" {:root "resources/public/media"})
                            (vw/assets "/static/*" {:root "resources/public/static"})
                            (vw/router routes))]

    (log/info "Starting http server on" (:http-server-port cfg/config) "port.")
    (vh/server ctx {:handler handler
                    :port (:http-server-port cfg/config)})))

(def num-cpus
  (delay (.availableProcessors (Runtime/getRuntime))))

(defstate server
  :start (let [vf (vc/verticle {:on-start on-start})]
           @(vc/deploy! system vf {:instances @num-cpus})))


