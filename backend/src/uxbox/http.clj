;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http
  (:require [mount.core :refer [defstate]]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as rr]
            [uxbox.config :as cfg]
            [uxbox.http.middleware :refer [handler
                                           middleware
                                           options-handler
                                           authorization-middleware]]
            [uxbox.api.auth :as api-auth]
            [uxbox.api.pages :as api-pages]
            [uxbox.api.users :as api-users]
            [uxbox.api.icons :as api-icons]
            [uxbox.api.images :as api-images]
            [uxbox.api.kvstore :as api-kvstore]
            [uxbox.api.projects :as api-projects]
            [uxbox.api.svg :as api-svg]
            [uxbox.util.transit :as t]))

(def ^:private router-options
  {::rr/default-options-handler options-handler
   :data {:middleware middleware}})

(def routes
  [["/media/*" (rr/create-resource-handler {:root "public/media"})]
   ["/static/*" (rr/create-resource-handler {:root "public/static"})]

   ["/api/auth"
    ["/login" {:post (handler #'api-auth/login)}]
    ["/logout" {:post (handler #'api-auth/logout)}]
    ["/register" {:post (handler #'api-auth/register)}]
    ["/recovery/:token" {:get (handler #'api-auth/register)}]
    ["/recovery" {:post (handler #'api-auth/request-recovery)
                  :get (handler #'api-auth/recover-password)}]]

   ["/api" {:middleware [authorization-middleware]}
    ;; KVStore
    ["/kvstore/:key" {:put (handler #'api-kvstore/upsert)
                      :get (handler #'api-kvstore/retrieve)
                      :delete (handler #'api-kvstore/delete)}]

    ["/svg/parse" {:post (handler #'api-svg/parse)}]

    ;; Projects
    ["/projects" {:get (handler #'api-projects/list-projects)
                  :post (handler #'api-projects/create-project)}]
    ["/projects/by-token/:token" {:get (handler #'api-projects/get-project-by-share-token)}]
    ["/projects/:id" {:put (handler #'api-projects/update-project)
                      :delete (handler #'api-projects/delete-project)}]

    ;; Pages
    ["/pages" {:get (handler #'api-pages/list-pages)
               :post (handler #'api-pages/create-page)}]
    ["/pages/:id" {:put (handler #'api-pages/update-page)
                   :delete (handler #'api-pages/delete-page)}]
    ["/pages/:id/metadata" {:put (handler #'api-pages/update-page-metadata)}]
    ["/pages/:id/history" {:get (handler #'api-pages/retrieve-page-history)}]
    ["/pages/:id/history/:hid" {:put (handler #'api-pages/update-page-history)}]

    ;; Profile
    ["/profile"
     ["/me" {:get (handler #'api-users/retrieve-profile)
             :put (handler #'api-users/update-profile)}]
     ["/me/password" {:put (handler #'api-users/update-password)}]
     ["/me/photo" {:post (handler #'api-users/update-photo)}]]

    ;; Library
    ["/library"
     ;; Icons
     ["/icon-collections/:id" {:put (handler #'api-icons/update-collection)
                               :delete (handler #'api-icons/delete-collection)}]
     ["/icon-collections" {:get (handler #'api-icons/list-collections)
                           :post (handler #'api-icons/create-collection)}]

     ["/icons/:id/copy" {:put (handler #'api-icons/copy-icon)}]

     ["/icons/:id" {:put (handler #'api-icons/update-icon)
                    :delete (handler #'api-icons/delete-icon)}]
     ["/icons" {:post (handler #'api-icons/create-icon)
                :get (handler #'api-icons/list-icons)}]

     ;; Images
     ["/image-collections/:id" {:put (handler #'api-images/update-collection)
                                :delete (handler #'api-images/delete-collection)}]
     ["/image-collections" {:post (handler #'api-images/create-collection)
                            :get (handler #'api-images/list-collections)}]
     ["/images/:id/copy" {:put (handler #'api-images/copy-image)}]
     ["/images/:id" {:get (handler #'api-images/retrieve-image)
                     :delete (handler #'api-images/delete-image)
                     :put (handler #'api-images/update-image)}]
     ["/images" {:post (handler #'api-images/create-image)
                 :get (handler #'api-images/list-images)}]
     ]

    ]])


;; --- State Initialization
(def app
  (delay
    (let [router (rr/router routes router-options)]
      (rr/ring-handler router (rr/create-default-handler)))))

(defn- start-server
  [config]
  (jetty/run-jetty @app {:join? false
                         :async? true
                         :daemon? false
                         :port (:http-server-port config)}))

(defstate server
  :start (start-server cfg/config)
  :stop (.stop server))
