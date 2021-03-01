;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.metrics :as mtx]
   [app.util.log4j :refer [update-thread-context!]]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [reitit.ring :as rr]
   [ring.adapter.jetty9 :as jetty])
  (:import
   org.eclipse.jetty.server.Server
   org.eclipse.jetty.server.handler.ErrorHandler
   org.eclipse.jetty.server.handler.StatisticsHandler))

(s/def ::handler fn?)
(s/def ::ws (s/map-of ::us/string fn?))
(s/def ::port ::cfg/http-server-port)
(s/def ::name ::us/string)

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req-un [::handler ::port]
          :opt-un [::ws ::name ::mtx/metrics]))

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "http"}
         (d/without-nils cfg)))

(defmethod ig/init-key ::server
  [_ {:keys [handler ws port name metrics] :as opts}]
  (log/infof "starting '%s' server on port %s." name port)
  (let [pre-start (fn [^Server server]
                    (let [handler (doto (ErrorHandler.)
                                    (.setShowStacks true)
                                    (.setServer server))]
                      (.setErrorHandler server ^ErrorHandler handler)
                      (when metrics
                        (let [stats (new StatisticsHandler)]
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

        server    (jetty/run-jetty handler options)]
    (assoc opts :server server)))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as opts}]
  (log/infof "stoping '%s' server on port %s." name port)
  (jetty/stop-server server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Main Handler (Router)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare create-router)

(s/def ::rpc map?)
(s/def ::session map?)
(s/def ::metrics map?)
(s/def ::oauth map?)
(s/def ::storage map?)
(s/def ::assets map?)
(s/def ::feedback fn?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::session ::metrics ::oauth ::storage ::assets ::feedback]))

(defmethod ig/init-key ::router
  [_ cfg]
  (let [handler (rr/ring-handler
                 (create-router cfg)
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
              (update-thread-context! cdata)
              (log/errorf e "unhandled exception: %s (id: %s)" (ex-message e) (str (:id cdata)))
              {:status 500
               :body "internal server error"})
            (catch Throwable e
              (log/errorf e "unhandled exception: %s" (ex-message e))
              {:status 500
               :body "internal server error"})))))))

(defn- create-router
  [{:keys [session rpc oauth metrics svgparse assets feedback] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]

    ["/assets" {:middleware [[middleware/format-response-body]
                             [middleware/errors errors/handle]]}
     ["/by-id/:id" {:get (:objects-handler assets)}]
     ["/by-file-media-id/:id" {:get (:file-objects-handler assets)}]
     ["/by-file-media-id/:id/thumbnail" {:get (:file-thumbnails-handler assets)}]]

    ["/dbg"
     ["/error-by-id/:id" {:get (:error-report-handler cfg)}]]

    ["/webhooks"
     ["/sns" {:post (:sns-webhook cfg)}]]

    ["/api" {:middleware [[middleware/format-response-body]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/parse-request-body]
                          [middleware/errors errors/handle]
                          [middleware/cookies]]}

     ["/svg" {:post svgparse}]
     ["/feedback" {:middleware [(:middleware session)]
                   :post feedback}]

     ["/oauth"
      ["/google" {:post (get-in oauth [:google :handler])}]
      ["/google/callback" {:get (get-in oauth [:google :callback-handler])}]

      ["/gitlab" {:post (get-in oauth [:gitlab :handler])}]
      ["/gitlab/callback" {:get (get-in oauth [:gitlab :callback-handler])}]

      ["/github" {:post (get-in oauth [:github :handler])}]
      ["/github/callback" {:get (get-in oauth [:github :callback-handler])}]]

     ["/rpc" {:middleware [(:middleware session)]}
      ["/query/:type" {:get (:query-handler rpc)}]
      ["/mutation/:type" {:post (:mutation-handler rpc)}]]]]))
