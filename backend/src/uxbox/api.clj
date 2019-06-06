;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api
  (:require [mount.core :refer [defstate]]
            [clojure.pprint :refer [pprint]]
            [uxbox.config :as cfg]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.adapter.jetty :as jetty]
            [promesa.core :as p]
            [reitit.core :as rc]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            ;; [reitit.dev.pretty :as pretty]
            [uxbox.api.middleware :as api-middleware :refer [handler]]
            [uxbox.api.auth :as api-auth]
            [uxbox.api.projects :as api-projects]
            [uxbox.api.pages :as api-pages]
            [uxbox.api.errors :as api-errors]
            [muuntaja.core :as m]
            [uxbox.util.transit :as t]
            [uxbox.util.data :refer [normalize-attrs]]
            [uxbox.util.exceptions :as ex]
            [uxbox.util.uuid :as uuid]))

;; --- Top Level Handlers

(defn- welcome-api
  "A GET entry point for the api that shows
  a welcome message."
  [context]
  (let [body {:message "Welcome to UXBox api."}]
    {:status 200
     :body {:query-params (:query-params context)
            :form-params (:form-params context)
            :body-params (:body-params context)
            :path-params (:path-params context)
            :params (:params context)}}))

;; --- Routes

(def routes
  (ring/router
   [["/media/*" (ring/create-resource-handler {:root "public/media"})]
    ["/static/*" (ring/create-resource-handler {:root "public/static"})]

    ["/auth/login" {:post (handler #'api-auth/login)}]

    ["/api" {:middleware [api-auth/authorization-middleware]}
     ["/echo" (handler #'welcome-api)]
     ["/projects" {:get (handler #'api-projects/list)
                   :post (handler #'api-projects/create)}]
     ["/projects/by-token/:token" {:get (handler #'api-projects/get-by-share-token)}]
     ["/projects/:id" {:put (handler #'api-projects/update)
                       :delete (handler #'api-projects/delete)}]
     ["/pages" {:get (handler #'api-pages/list)}]
     ["/pages/:id" {:put (handler #'api-pages/update)
                    :delete (handler #'api-pages/delete)}]
     ["/pages/:id/metatata" {:put (handler #'api-pages/update-metadata)}]
     ["/pages/:id/history" {:get (handler #'api-pages/retrieve-history)}]
     ]]

   {;;:reitit.middleware/transform dev/print-request-diffs
    :data {:muuntaja (m/create
                      (update-in m/default-options [:formats "application/transit+json"]
                                 merge {:encoder-opts {:handlers t/+write-handlers+}
                                        :decoder-opts {:handlers t/+read-handlers+}}))
           :middleware [
                        ;; {:name "CORS Middleware"
                        ;;  :wrap #(wrap-cors %
                        ;;                    :access-control-allow-origin [#".*"]
                        ;;                    :access-control-allow-methods [:get :put :post :delete]
                        ;;                    :access-control-allow-headers ["x-requested-with"
                        ;;                                                   "content-type"
                        ;;                                                   "authorization"])}
                        [wrap-session {:store (cookie-store {:key "a 16-byte secret"})
                                       :cookie-name "session"
                                       :cookie-attrs {:same-site :lax
                                                      :http-only true}}]
                        parameters/parameters-middleware
                        api-middleware/normalize-params-middleware
                        ;; content-negotiation
                        muuntaja/format-negotiate-middleware
                        ;; encoding response body
                        muuntaja/format-response-middleware
                        ;; exception handling
                        api-errors/exception-middleware
                        ;; decoding request body
                        muuntaja/format-request-middleware
                        ;; validation
                        api-middleware/parameters-validation-middleware
                        ;; multipart
                        multipart/multipart-middleware]}}))

(def app
  (ring/ring-handler routes (ring/create-default-handler)))

;; --- State Initialization

(defn- start-server
  [config]
  (jetty/run-jetty app {:join? false
                        :async? true
                        :daemon? true
                        :port (:http-server-port config)}))

(defstate server
  :start (start-server cfg/config)
  :stop (.stop server))
