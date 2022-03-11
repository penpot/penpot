;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.http.doc :as doc]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.metrics :as mtx]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [reitit.core :as r]
   [reitit.middleware :as rr]
   [yetti.adapter :as yt]
   [yetti.request :as yrq]
   [yetti.response :as yrs]))

(declare wrap-router)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP SERVER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::handler fn?)
(s/def ::router some?)
(s/def ::port integer?)
(s/def ::host string?)
(s/def ::name string?)

(s/def ::max-body-size integer?)
(s/def ::max-multipart-body-size integer?)
(s/def ::io-threads integer?)
(s/def ::worker-threads integer?)

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "http"
          :port 6060
          :host "0.0.0.0"
          :max-body-size (* 1024 1024 24)             ; 24 MiB
          :max-multipart-body-size (* 1024 1024 120)} ; 120 MiB
         (d/without-nils cfg)))

(defmethod ig/pre-init-spec ::server [_]
  (s/and
   (s/keys :req-un [::port ::host ::name ::max-body-size ::max-multipart-body-size]
           :opt-un [::router ::handler ::io-threads ::worker-threads ::wrk/executor])
   (fn [cfg]
     (or (contains? cfg :router)
         (contains? cfg :handler)))))

(defmethod ig/init-key ::server
  [_ {:keys [handler router port name host] :as cfg}]
  (l/info :hint "starting http server" :port port :host host :name name)
  (let [options {:http/port port
                 :http/host host
                 :http/max-body-size (:max-body-size cfg)
                 :http/max-multipart-body-size (:max-multipart-body-size cfg)
                 :xnio/io-threads (:io-threads cfg)
                 :xnio/worker-threads (:worker-threads cfg)
                 :xnio/dispatch (:executor cfg)
                 :ring/async true}
        handler (if (some? router)
                  (wrap-router cfg router)
                  handler)
        server  (yt/server handler (d/without-nils options))]
    (assoc cfg :server (yt/start! server))))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as cfg}]
  (l/info :msg "stoping http server" :name name :port port)
  (yt/stop! server))

(defn- not-found-handler
  [_ respond _]
  (respond (yrs/response 404)))

(defn- ring-handler
  [router]
  (fn [request respond raise]
    (if-let [match (r/match-by-path router (yrq/path request))]
      (let [params  (:path-params match)
            result  (:result match)
            handler (or (:handler result) not-found-handler)
            request (-> request
                        (assoc :path-params params)
                        (update :params merge params))]
        (handler request respond raise))
      (not-found-handler request respond raise))))

(defn- wrap-router
  [_ router]
  (let [handler (ring-handler router)]
    (fn [request respond _]
      (handler request respond
               (fn [cause]
                 (l/error :hint "unexpected error processing request"
                          ::l/context (errors/get-context request)
                          :query-string (yrq/query request)
                          :cause cause)
                 (respond (yrs/response 500 "internal server error")))))))

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
(s/def ::session map?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::mtx/metrics ::ws ::oauth ::storage ::assets
                   ::session ::feedback ::awsns-handler ::debug ::audit-handler]))

(defmethod ig/init-key ::router
  [_ {:keys [ws session rpc oauth metrics assets feedback debug] :as cfg}]
  (rr/router
   [["" {:middleware [[middleware/server-timing]
                      [middleware/format-response]
                      [middleware/errors errors/handle]
                      [middleware/restrict-methods]]}
     ["/metrics" {:handler (:handler metrics)}]
     ["/assets" {:middleware [(:middleware session)]}
      ["/by-id/:id" {:handler (:objects-handler assets)}]
      ["/by-file-media-id/:id" {:handler (:file-objects-handler assets)}]
      ["/by-file-media-id/:id/thumbnail" {:handler (:file-thumbnails-handler assets)}]]

     ["/dbg" {:middleware [[middleware/params]
                           [middleware/parse-request]
                           (:middleware session)]}
      ["" {:handler (:index debug)}]
      ["/error-by-id/:id" {:handler (:retrieve-error debug)}]
      ["/error/:id" {:handler (:retrieve-error debug)}]
      ["/error" {:handler (:retrieve-error-list debug)}]
      ["/file/data" {:handler (:file-data debug)}]
      ["/file/changes" {:handler (:retrieve-file-changes debug)}]]

     ["/webhooks"
      ["/sns" {:handler (:awsns-handler cfg)
               :allowed-methods #{:post}}]]

     ["/ws/notifications" {:middleware [[middleware/params]
                                        [middleware/parse-request]
                                        (:middleware session)]
                           :handler ws
                           :allowed-methods #{:get}}]

     ["/api" {:middleware [[middleware/cors]
                           [middleware/params]
                           [middleware/parse-request]
                           (:middleware session)]}
      ["/health" {:handler (:health-check debug)}]
      ["/_doc" {:handler (doc/handler rpc)
                :allowed-methods #{:get}}]
      ["/feedback" {:handler feedback
                    :allowed-methods #{:post}}]

      ["/auth/oauth/:provider" {:handler (:handler oauth)
                                :allowed-methods #{:post}}]
      ["/auth/oauth/:provider/callback" {:handler (:callback-handler oauth)
                                         :allowed-methods #{:get}}]

      ["/audit/events" {:handler (:audit-handler cfg)
                        :allowed-methods #{:post}}]

      ["/rpc"
       ["/query/:type" {:handler (:query-handler rpc)}]
       ["/mutation/:type" {:handler (:mutation-handler rpc)
                           :allowed-methods #{:post}}]]]]]))
