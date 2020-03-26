;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.web
  "High level api for http servers."
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [reitit.core :as rt]
   [reitit.middleware :as rmw]
   [vertx.http :as http]
   [vertx.impl :as impl])
  (:import
   clojure.lang.IPersistentMap
   clojure.lang.Keyword
   io.vertx.core.Future
   io.vertx.core.Handler
   io.vertx.core.Vertx
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.Cookie
   io.vertx.core.http.HttpServer
   io.vertx.core.http.HttpServerOptions
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.ServerWebSocket
   io.vertx.ext.web.Route
   io.vertx.ext.web.Router
   io.vertx.ext.web.RoutingContext
   io.vertx.ext.web.handler.BodyHandler
   io.vertx.ext.web.handler.LoggerHandler
   io.vertx.ext.web.handler.ResponseTimeHandler
   io.vertx.ext.web.handler.StaticHandler))

;; --- Public Api

(s/def ::wrap-handler
  (s/or :fn fn?
        :vec (s/every fn? :kind vector?)))

(defn- ->request
  [^RoutingContext routing-context]
  (let [^HttpServerRequest request (.request ^RoutingContext routing-context)
        ^HttpServerResponse response (.response ^RoutingContext routing-context)
        ^Vertx system (.vertx routing-context)]
    {:body (.getBody routing-context)
     :path (.path request)
     :headers (http/->headers (.headers request))
     :method (-> request .rawMethod .toLowerCase keyword)
     ::http/request request
     ::http/response response
     ;; ::execution-context (.getContext system)
     ::routing-context routing-context}))

(defn handler
  "Wraps a user defined funcion based handler into a vertx-web aware
  handler (with support for multipart uploads)."
  [vsm & handlers]
  (let [^Vertx vsm (impl/resolve-system vsm)
        ^Router router (Router/router vsm)]
    (reduce #(%2 %1) router handlers)))

(defn assets
  ([path] (assets path {}))
  ([path {:keys [root] :or {root "public"} :as options}]
   (fn [^Router router]
     (let [^Route route (.route router path)
           ^Handler handler (doto (StaticHandler/create)
                              (.setCachingEnabled false)
                              (.setWebRoot root)
                              (.setDirectoryListing true))]
       (.handler route handler)
       ;; A hack for lie to body handler that request is already handled.
       (.handler route
                 (reify Handler
                   (handle [_ rc]
                     (.put ^RoutingContext rc "__body-handled" true)
                     (.next ^RoutingContext rc))))
       router))))

(defn- default-handler
  [ctx]
  (if (::match ctx)
    {:status 405}
    {:status 404}))

(defn- default-on-error
  [err req]
  (log/error err)
  {:status 500
   :body "Internal server error!\n"})

(defn- router-handler
  [router {:keys [path method] :as ctx}]
  (if-let [{:keys [result path-params] :as match} (rt/match-by-path router path)]
    (let [handler-fn (:handler result)
          ctx (assoc ctx ::match match :path-params path-params)]
      (handler-fn ctx))
    (default-handler ctx)))

(defn router
  ([routes] (router routes {}))
  ([routes {:keys [delete-uploads?
                   upload-dir
                   on-error
                   log-requests?
                   time-response?]
            :or {delete-uploads? true
                 upload-dir "/tmp/vertx.uploads"
                 on-error default-on-error
                 log-requests? false
                 time-response? true}
            :as options}]
   (let [rtr (rt/router routes {:compile rmw/compile-result})
         rtf #(router-handler rtr %)]
     (fn [^Router router]
       (let [^Route route (.route router)]
         (when time-response? (.handler route (ResponseTimeHandler/create)))
         (when log-requests? (.handler route (LoggerHandler/create)))

         (doto route
           (.failureHandler
            (reify Handler
              (handle [_ rc]
                (let [err (.failure ^RoutingContext rc)
                      req (.get ^RoutingContext rc "vertx$clj$req")]
                  (-> (p/do! (on-error err req))
                      (http/-handle-response req))))))

           (.handler
            (doto (BodyHandler/create true)
              (.setDeleteUploadedFilesOnEnd delete-uploads?)
              (.setUploadsDirectory upload-dir)))

           (.handler
            (reify Handler
              (handle [_ rc]
                (let [req (->request rc)
                      efn (fn [err]
                            (.put ^RoutingContext rc "vertx$clj$req" req)
                            (.fail ^RoutingContext rc ^Throwable err))]
                  (try
                    (let [result (rtf req)]
                      (-> (http/-handle-response result req)
                          (p/catch' efn)))
                    (catch Exception err
                      (efn err)))))))))
         router))))
