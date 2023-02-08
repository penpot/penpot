;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http
  (:require
   [app.auth.oidc :as-alias oidc]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.db :as-alias db]
   [app.http.access-token :as actoken]
   [app.http.assets :as-alias assets]
   [app.http.awsns :as-alias awsns]
   [app.http.debug :as-alias debug]
   [app.http.errors :as errors]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.http.websocket :as-alias ws]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias rpc.doc]
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

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {::port 6060
          ::host "0.0.0.0"
          ::max-body-size (* 1024 1024 30)             ; 30 MiB
          ::max-multipart-body-size (* 1024 1024 120)} ; 120 MiB
         (d/without-nils cfg)))

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req [::port ::host]
          :opt [::max-body-size
                ::max-multipart-body-size
                ::router
                ::handler
                ::io-threads
                ::wrk/executor]))

(defmethod ig/init-key ::server
  [_ {:keys [::handler ::router ::host ::port] :as cfg}]
  (l/info :hint "starting http server" :port port :host host)
  (let [options {:http/port port
                 :http/host host
                 :http/max-body-size (::max-body-size cfg)
                 :http/max-multipart-body-size (::max-multipart-body-size cfg)
                 :xnio/io-threads (::io-threads cfg)
                 :xnio/dispatch (::wrk/executor cfg)
                 :ring/async true}

        handler (cond
                  (some? router)
                  (wrap-router router)

                  (some? handler)
                  handler

                  :else
                  (throw (UnsupportedOperationException. "handler or router are required")))

        options (d/without-nils options)
        server  (yt/server handler options)]

    (assoc cfg ::server (yt/start! server))))

(defmethod ig/halt-key! ::server
  [_ {:keys [::server ::port] :as cfg}]
  (l/info :msg "stopping http server" :port port)
  (yt/stop! server))

(defn- not-found-handler
  [_ respond _]
  (respond (yrs/response 404)))

(defn- wrap-router
  [router]
  (letfn [(handler [request respond raise]
            (if-let [match (r/match-by-path router (yrq/path request))]
              (let [params  (:path-params match)
                    result  (:result match)
                    handler (or (:handler result) not-found-handler)
                    request (assoc request :path-params params)]
                (handler request respond raise))
              (not-found-handler request respond raise)))

          (on-error [cause request respond]
            (let [{:keys [body] :as response} (errors/handle cause request)]
              (respond
               (cond-> response
                 (map? body)
                 (-> (update :headers assoc "content-type" "application/transit+json")
                     (assoc :body (t/encode-str body {:type :json-verbose})))))))]

    (fn [request respond _]
      (try
        (handler request respond #(on-error % request respond))
        (catch Throwable cause
          (on-error cause request respond))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP ROUTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req [::session/manager
                ::actoken/manager
                ::ws/routes
                ::rpc/routes
                ::rpc.doc/routes
                ::oidc/routes
                ::assets/routes
                ::debug/routes
                ::db/pool
                ::mtx/routes
                ::awsns/routes]))

(defmethod ig/init-key ::router
  [_ cfg]
  (rr/router
   [["" {:middleware [[mw/server-timing]
                      [mw/format-response]
                      [mw/params]
                      [mw/parse-request]
                      [session/soft-auth cfg]
                      [actoken/soft-auth cfg]
                      [mw/errors errors/handle]
                      [mw/restrict-methods]]}

     (::mtx/routes cfg)
     (::assets/routes cfg)
     (::debug/routes cfg)

     ["/webhooks"
      (::awsns/routes cfg)]

     (::ws/routes cfg)

     ["/api" {:middleware [[mw/cors]]}
      (::oidc/routes cfg)
      (::rpc.doc/routes cfg)
      (::rpc/routes cfg)]]]))
