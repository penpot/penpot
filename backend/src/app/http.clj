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

(s/def ::handler fn?)
(s/def ::router some?)
(s/def ::port ::us/integer)
(s/def ::host ::us/string)
(s/def ::name ::us/string)

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req-un [::port]
          :opt-un [::name ::mtx/metrics ::router ::handler ::host]))

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "http"} (d/without-nils cfg)))

(defn- instrument-metrics
  [^Server server metrics]
  (let [stats (doto (StatisticsHandler.)
                (.setHandler (.getHandler server)))]
    (.setHandler server stats)
    (mtx/instrument-jetty! (:registry metrics) stats)
    server))

(defmethod ig/init-key ::server
  [_ {:keys [handler router port name metrics] :as opts}]
  (l/info :msg "starting http server" :port port :name name)
  (let [options {:http/port port}
        handler (cond
                  (fn? handler)  handler
                  (some? router) (wrap-router router)
                  :else (ex/raise :type :internal
                                  :code :invalid-argument
                                  :hint "Missing `handler` or `router` option."))
        server  (-> (yt/server handler options)
                    (cond-> metrics (instrument-metrics metrics)))]
    (assoc opts :server (yt/start! server))))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as opts}]
  (l/info :msg "stoping http server" :name name :port port)
  (yt/stop! server))

(defn- wrap-router
  [router]
  (let [default (rr/routes
                 (rr/create-resource-handler {:path "/"})
                 (rr/create-default-handler))
        options {:middleware [middleware/server-timing]}
        handler (rr/ring-handler router default options)]
    (fn [request]
      (try
        (handler request)
        (catch Throwable e
          (l/with-context (errors/get-error-context request e)
            (l/error :hint (ex-message e) :cause e)
            {:status 500 :body "internal server error"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Router
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::rpc map?)
(s/def ::session map?)
(s/def ::oauth map?)
(s/def ::storage map?)
(s/def ::assets map?)
(s/def ::feedback fn?)
(s/def ::ws fn?)
(s/def ::audit-http-handler fn?)
(s/def ::debug map?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::session ::mtx/metrics ::ws
                   ::oauth ::storage ::assets ::feedback
                   ::debug ::audit-http-handler]))

(defmethod ig/init-key ::router
  [_ {:keys [ws session rpc oauth metrics assets feedback debug] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]
    ["/assets" {:middleware [[middleware/format-response-body]
                             [middleware/errors errors/handle]
                             [middleware/cookies]
                             (:middleware session)]}
     ["/by-id/:id" {:get (:objects-handler assets)}]
     ["/by-file-media-id/:id" {:get (:file-objects-handler assets)}]
     ["/by-file-media-id/:id/thumbnail" {:get (:file-thumbnails-handler assets)}]]

    ["/dbg" {:middleware [[middleware/params]
                          [middleware/keyword-params]
                          [middleware/format-response-body]
                          [middleware/errors errors/handle]
                          [middleware/cookies]
                          [(:middleware session)]]}
     ["/error-by-id/:id" {:get (:retrieve-error debug)}]
     ["/error/:id" {:get (:retrieve-error debug)}]
     ["/error" {:get (:retrieve-error-list debug)}]
     ["/file/data/:id" {:get (:retrieve-file-data debug)}]
     ["/file/changes/:id" {:get (:retrieve-file-changes debug)}]]

    ["/webhooks"
     ["/sns" {:post (:sns-webhook cfg)}]]

    ["/ws/notifications"
     {:middleware [[middleware/params]
                   [middleware/keyword-params]
                   [middleware/format-response-body]
                   [middleware/errors errors/handle]
                   [middleware/cookies]
                   [(:middleware session)]]
      :get ws}]

    ["/api" {:middleware [[middleware/cors]
                          [middleware/etag]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/format-response-body]
                          [middleware/parse-request-body]
                          [middleware/errors errors/handle]
                          [middleware/cookies]]}

     ["/_doc" {:get (doc/handler rpc)}]

     ["/feedback" {:middleware [(:middleware session)]
                   :post feedback}]
     ["/auth/oauth/:provider" {:post (:handler oauth)}]
     ["/auth/oauth/:provider/callback" {:get (:callback-handler oauth)}]

     ["/audit/events" {:middleware [(:middleware session)]
                       :post (:audit-http-handler cfg)}]

     ["/rpc" {:middleware [(:middleware session)]}
      ["/query/:type" {:get (:query-handler rpc)
                       :post (:query-handler rpc)}]
      ["/mutation/:type" {:post (:mutation-handler rpc)}]]]]))
