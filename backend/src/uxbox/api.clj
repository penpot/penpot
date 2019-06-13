;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api
  (:require [mount.core :refer [defstate]]
            [uxbox.config :as cfg]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [uxbox.api.middleware :refer [handler router-options]]
            [uxbox.api.auth :as api-auth]
            [uxbox.api.pages :as api-pages]
            [uxbox.api.projects :as api-projects]))

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
     ["/pages" {:get (handler #'api-pages/list)
                :post (handler #'api-pages/create)}]
     ["/pages/:id" {:put (handler #'api-pages/update)
                    :delete (handler #'api-pages/delete)}]
     ["/pages/:id/metadata" {:put (handler #'api-pages/update-metadata)}]
     ["/pages/:id/history" {:get (handler #'api-pages/retrieve-history)}]
     ["/pages/:id/history/:hid" {:put (handler #'api-pages/update-history)}]
     ]]
   router-options))

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
