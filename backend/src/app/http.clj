;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.http.doc :as doc]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.metrics :as mtx]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [reitit.ring :as rr]
   [yetti.adapter :as yt])
  (:import
   org.eclipse.jetty.server.Server
   org.eclipse.jetty.server.handler.StatisticsHandler))

(declare wrap-router)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP SERVER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::session map?)
(s/def ::handler fn?)
(s/def ::router some?)
(s/def ::port ::us/integer)
(s/def ::host ::us/string)
(s/def ::name ::us/string)
(s/def ::max-threads ::cf/http-server-max-threads)
(s/def ::min-threads ::cf/http-server-min-threads)

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "http"
          :min-threads 4
          :max-threads 60
          :port 6060
          :host "0.0.0.0"}
         (d/without-nils cfg)))

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req-un [::port ::host ::name ::min-threads ::max-threads ::session]
          :opt-un [::mtx/metrics ::router ::handler]))

(defn- instrument-metrics
  [^Server server metrics]
  (let [stats (doto (StatisticsHandler.)
                (.setHandler (.getHandler server)))]
    (.setHandler server stats)
    (mtx/instrument-jetty! (:registry metrics) stats)
    server))

(defmethod ig/init-key ::server
  [_ {:keys [handler router port name metrics host] :as cfg}]
  (l/info :hint "starting http server"
          :port port :host host :name name
          :min-threads (:min-threads cfg)
          :max-threads (:max-threads cfg))
  (let [options {:http/port port
                 :http/host host
                 :thread-pool/max-threads (:max-threads cfg)
                 :thread-pool/min-threads (:min-threads cfg)
                 :ring/async true}
        handler (cond
                  (fn? handler)  handler
                  (some? router) (wrap-router cfg router)
                  :else (ex/raise :type :internal
                                  :code :invalid-argument
                                  :hint "Missing `handler` or `router` option."))
        server  (-> (yt/server handler (d/without-nils options))
                    (cond-> metrics (instrument-metrics metrics)))]
    (assoc cfg :server (yt/start! server))))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as cfg}]
  (l/info :msg "stoping http server" :name name :port port)
  (yt/stop! server))

(defn- wrap-router
  [{:keys [session] :as cfg} router]
  (let [default (rr/routes
                 (rr/create-resource-handler {:path "/"})
                 (rr/create-default-handler))
        options {:middleware [[middleware/wrap-server-timing]
                              [middleware/cookies]
                              [(:middleware session)]]
                 :inject-match? false
                 :inject-router? false}
        handler (rr/ring-handler router default options)]
    (fn [request respond _]
      (handler request respond (fn [cause]
                                 (l/error :hint "unexpected error processing request"
                                          ::l/context (errors/get-error-context request cause)
                                          :query-string (:query-string request)
                                          :cause cause)
                                 (respond {:status 500 :body "internal server error"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP ROUTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::rpc map?)
(s/def ::oauth map?)
(s/def ::storage map?)
(s/def ::assets map?)
(s/def ::feedback fn?)
(s/def ::ws fn?)
(s/def ::audit-handler fn?)
(s/def ::debug map?)
(s/def ::awsns-handler fn?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::mtx/metrics ::ws ::oauth ::storage ::assets
                   ::feedback ::awsns-handler ::debug ::audit-handler]))

(defmethod ig/init-key ::router
  [_ {:keys [ws session rpc oauth metrics assets feedback debug] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]
    ["/assets" {:middleware [[middleware/format-response-body]
                             [middleware/errors errors/handle]]}
     ["/by-id/:id" {:get (:objects-handler assets)}]
     ["/by-file-media-id/:id" {:get (:file-objects-handler assets)}]
     ["/by-file-media-id/:id/thumbnail" {:get (:file-thumbnails-handler assets)}]]

    ["/dbg" {:middleware [[middleware/multipart-params]
                          [middleware/params]
                          [middleware/keyword-params]
                          [middleware/format-response-body]
                          [middleware/errors errors/handle]]}
     ["" {:get (:index debug)}]
     ["/error-by-id/:id" {:get (:retrieve-error debug)}]
     ["/error/:id" {:get (:retrieve-error debug)}]
     ["/error" {:get (:retrieve-error-list debug)}]
     ["/file/data" {:get (:retrieve-file-data debug)
                    :post (:upload-file-data debug)}]
     ["/file/changes" {:get (:retrieve-file-changes debug)}]]

    ["/webhooks"
     ["/sns" {:post (:awsns-handler cfg)}]]

    ["/ws/notifications"
     {:middleware [[middleware/params]
                   [middleware/keyword-params]
                   [middleware/format-response-body]
                   [middleware/errors errors/handle]]
      :get ws}]

    ["/api" {:middleware [[middleware/cors]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/format-response-body]
                          [middleware/parse-request-body]
                          [middleware/errors errors/handle]]}

     ["/health" {:get (:health-check debug)}]
     ["/_doc" {:get (doc/handler rpc)}]
     ["/feedback" {:middleware [(:middleware session)]
                   :post feedback}]
     ["/auth/oauth/:provider" {:post (:handler oauth)}]
     ["/auth/oauth/:provider/callback" {:get (:callback-handler oauth)}]

     ["/audit/events" {:post (:audit-handler cfg)}]

     ["/rpc"
      ["/query/:type" {:get (:query-handler rpc)
                       :post (:query-handler rpc)}]
      ["/mutation/:type" {:post (:mutation-handler rpc)}]]]]))
