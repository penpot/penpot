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
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.db :as-alias db]
   [app.http.access-token :as actoken]
   [app.http.assets :as-alias assets]
   [app.http.awsns :as-alias awsns]
   [app.http.debug :as-alias debug]
   [app.http.errors :as errors]
   [app.http.management :as mgmt]
   [app.http.middleware :as mw]
   [app.http.security :as sec]
   [app.http.session :as session]
   [app.http.websocket :as-alias ws]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [integrant.core :as ig]
   [reitit.core :as r]
   [reitit.middleware :as rr]
   [yetti.adapter :as yt]
   [yetti.request :as yreq]
   [yetti.response :as-alias yres]))

(declare router-handler)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP SERVER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-params
  {::port 6060
   ::host "0.0.0.0"
   ::max-body-size 31457280              ; default  30 MiB
   ::max-multipart-body-size 367001600}) ; default 350 MiB

(defmethod ig/expand-key ::server
  [k v]
  {k (merge default-params (d/without-nils v))})

(def ^:private schema:server-params
  [:map
   [::port ::sm/int]
   [::host ::sm/text]
   [::io-threads {:optional true} ::sm/int]
   [::max-worker-threads {:optional true} ::sm/int]
   [::max-body-size {:optional true} ::sm/int]
   [::max-multipart-body-size {:optional true} ::sm/int]
   [::router {:optional true} [:fn r/router?]]
   [::handler {:optional true} ::sm/fn]])

(defmethod ig/assert-key ::server
  [_ params]
  (assert (sm/check schema:server-params params)))

(defmethod ig/init-key ::server
  [_ {:keys [::handler ::router ::host ::port ::mtx/metrics] :as cfg}]
  (l/info :hint "starting http server" :port port :host host)
  (let [on-dispatch
        (fn [_ start-at-ns]
          (let [timing (- (System/nanoTime) start-at-ns)
                timing (int (/ timing 1000000))]
            (mtx/run! metrics
                      :id :http-server-dispatch-timing
                      :val timing)))

        options
        {:http/port port
         :http/host host
         :http/max-body-size (::max-body-size cfg)
         :http/max-multipart-body-size (::max-multipart-body-size cfg)
         :xnio/direct-buffers false
         :xnio/io-threads (::io-threads cfg)
         :xnio/max-worker-threads (::max-worker-threads cfg)
         :ring/compat :ring2
         :events/on-dispatch on-dispatch
         :socket/backlog 4069}

        handler
        (cond
          (some? router)
          (router-handler router)

          (some? handler)
          handler

          :else
          (throw (UnsupportedOperationException. "handler or router are required")))

        server
        (yt/server handler (d/without-nils options))]

    (assoc cfg ::server (yt/start! server))))

(defmethod ig/halt-key! ::server
  [_ {:keys [::server ::port] :as cfg}]
  (l/info :msg "stopping http server" :port port)
  (yt/stop! server))

(defn- not-found-handler
  [_]
  {::yres/status 404})

(defn- router-handler
  [router]
  (letfn [(resolve-handler [request]
            (if-let [match (r/match-by-path router (yreq/path request))]
              (let [params  (:path-params match)
                    result  (:result match)
                    handler (or (:handler result) not-found-handler)
                    request (assoc request :path-params params)]
                (partial handler request))
              (partial not-found-handler request)))

          (on-error [cause request]
            (let [{:keys [::yres/body] :as response} (errors/handle cause request)]
              (cond-> response
                (map? body)
                (-> (update ::yres/headers assoc "content-type" "application/transit+json")
                    (assoc ::yres/body (t/encode-str body {:type :json-verbose}))))))]

    (fn [request]
      (let [handler (resolve-handler request)]
        (try
          (handler)
          (catch Throwable cause
            (on-error cause request)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP ROUTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:routes
  [:vector :any])

(def ^:private schema:router-params
  [:map
   [::ws/routes schema:routes]
   [::rpc/routes schema:routes]
   [::oidc/routes schema:routes]
   [::assets/routes schema:routes]
   [::debug/routes schema:routes]
   [::mtx/routes schema:routes]
   [::awsns/routes schema:routes]
   [::mgmt/routes schema:routes]
   ::session/manager
   ::setup/props
   ::db/pool])

(defmethod ig/assert-key ::router
  [_ params]
  (assert (sm/check schema:router-params params)))

(defmethod ig/init-key ::router
  [_ cfg]
  (rr/router
   [["" {:middleware [[mw/server-timing]
                      [sec/sec-fetch-metadata]
                      [mw/params]
                      [mw/format-response]
                      [mw/auth {:bearer (partial session/decode-token cfg)
                                :cookie (partial session/decode-token cfg)
                                :token  (partial actoken/decode-token cfg)}]
                      [mw/parse-request]
                      [mw/errors errors/handle]
                      [mw/restrict-methods]]}

     (::mtx/routes cfg)
     (::assets/routes cfg)
     (::debug/routes cfg)

     ["/webhooks"
      (::awsns/routes cfg)]

     ["/management"
      (::mgmt/routes cfg)]

     (::ws/routes cfg)
     (::oidc/routes cfg)
     (::rpc/routes cfg)]]))
