;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.metrics :as mtx]
   [app.util.logging :as l]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [reitit.ring :as rr]
   [ring.adapter.jetty9 :as jetty])
  (:import
   org.eclipse.jetty.server.Server
   org.eclipse.jetty.server.handler.ErrorHandler
   org.eclipse.jetty.server.handler.StatisticsHandler))

(declare router-handler)

(s/def ::handler fn?)
(s/def ::router some?)
(s/def ::ws (s/map-of ::us/string fn?))
(s/def ::port ::us/integer)
(s/def ::name ::us/string)

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req-un [::port]
          :opt-un [::ws ::name ::mtx/metrics ::router ::handler]))

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "http"} (d/without-nils cfg)))

(defmethod ig/init-key ::server
  [_ {:keys [handler router ws port name metrics] :as opts}]
  (l/info :msg "starting http server" :port port :name name)
  (let [pre-start (fn [^Server server]
                    (let [handler (doto (ErrorHandler.)
                                    (.setShowStacks true)
                                    (.setServer server))]
                      (.setErrorHandler server ^ErrorHandler handler)
                      (when metrics
                        (let [stats (StatisticsHandler.)]
                          (.setHandler ^StatisticsHandler stats (.getHandler server))
                          (.setHandler server stats)
                          (mtx/instrument-jetty! (:registry metrics) stats)))))

        options   (merge
                   {:port port
                    :h2c? true
                    :join? false
                    :allow-null-path-info true
                    :configurator pre-start}
                   (when (seq ws)
                     {:websockets ws}))

        handler   (cond
                    (fn? handler)  handler
                    (some? router) (router-handler router)
                    :else (ex/raise :type :internal
                                    :code :invalid-argument
                                    :hint "Missing `handler` or `router` option."))

        server    (jetty/run-jetty handler options)]
    (assoc opts :server server)))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as opts}]
  (l/info :msg "stoping http server"
          :name name
          :port port)
  (jetty/stop-server server))

(defn- router-handler
  [router]
  (let [handler (rr/ring-handler router
                                 (rr/routes
                                  (rr/create-resource-handler {:path "/"})
                                  (rr/create-default-handler))
                                 {:middleware [middleware/server-timing]})]
    (fn [request]
      (try
        (handler request)
        (catch Throwable e
          (try
            (let [cdata (errors/get-error-context request e)]
              (l/update-thread-context! cdata)
              (l/error :hint "unhandled exception"
                       :message (ex-message e)
                       :error-id (str (:id cdata))
                       :cause e))
            {:status 500 :body "internal server error"}
            (catch Throwable e
              (l/error :hint "unhandled exception"
                       :message (ex-message e)
                       :cause e)
              {:status 500 :body "internal server error"})))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Main Handler (Router)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::rpc map?)
(s/def ::session map?)
(s/def ::oauth map?)
(s/def ::storage map?)
(s/def ::assets map?)
(s/def ::feedback fn?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::session ::mtx/metrics ::oauth ::storage ::assets ::feedback]))

(defmethod ig/init-key ::router
  [_ {:keys [session rpc oauth metrics assets feedback] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]
    ["/assets" {:middleware [[middleware/format-response-body]
                             [middleware/errors errors/handle]
                             [middleware/cookies]
                             (:middleware session)
                             middleware/activity-logger]}
     ["/by-id/:id" {:get (:objects-handler assets)}]
     ["/by-file-media-id/:id" {:get (:file-objects-handler assets)}]
     ["/by-file-media-id/:id/thumbnail" {:get (:file-thumbnails-handler assets)}]]

    ["/dbg"
     ["/error-by-id/:id" {:get (:error-report-handler cfg)}]]

    ["/webhooks"
     ["/sns" {:post (:sns-webhook cfg)}]]

    ["/api" {:middleware [[middleware/etag]
                          [middleware/format-response-body]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/parse-request-body]
                          [middleware/errors errors/handle]
                          [middleware/cookies]]}

     ["/feedback" {:middleware [(:middleware session)]
                   :post feedback}]

     ["/oauth"
      ["/google" {:post (get-in oauth [:google :handler])}]
      ["/google/callback" {:get (get-in oauth [:google :callback-handler])}]

      ["/gitlab" {:post (get-in oauth [:gitlab :handler])}]
      ["/gitlab/callback" {:get (get-in oauth [:gitlab :callback-handler])}]

      ["/github" {:post (get-in oauth [:github :handler])}]
      ["/github/callback" {:get (get-in oauth [:github :callback-handler])}]]

     ["/rpc" {:middleware [(:middleware session)
                           middleware/activity-logger]}
      ["/query/:type" {:get (:query-handler rpc)
                       :post (:query-handler rpc)}]
      ["/mutation/:type" {:post (:mutation-handler rpc)}]]]]))
