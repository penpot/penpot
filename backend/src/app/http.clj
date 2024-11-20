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
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.http.websocket :as-alias ws]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias rpc.doc]
   [app.setup :as-alias setup]
   [integrant.core :as ig]
   [promesa.exec :as px]
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
   ::max-body-size (* 1024 1024 30)             ; default 30 MiB
   ::max-multipart-body-size (* 1024 1024 120)}) ; default 120 MiB

(defmethod ig/expand-key ::server
  [k v]
  {k (merge default-params (d/without-nils v))})

(def ^:private schema:server-params
  [:map
   [::port ::sm/int]
   [::host ::sm/text]
   [::max-body-size {:optional true} ::sm/int]
   [::max-multipart-body-size {:optional true} ::sm/int]
   [::router {:optional true} [:fn r/router?]]
   [::handler {:optional true} ::sm/fn]])

(defmethod ig/assert-key ::server
  [_ params]
  (assert (sm/check schema:server-params params)))

(defmethod ig/init-key ::server
  [_ {:keys [::handler ::router ::host ::port] :as cfg}]
  (l/info :hint "starting http server" :port port :host host)
  (let [options {:http/port port
                 :http/host host
                 :http/max-body-size (::max-body-size cfg)
                 :http/max-multipart-body-size (::max-multipart-body-size cfg)
                 :xnio/io-threads (or (::io-threads cfg)
                                      (max 3 (px/get-available-processors)))
                 :xnio/dispatch :virtual
                 :ring/compat :ring2
                 :socket/backlog 4069}

        handler (cond
                  (some? router)
                  (router-handler router)

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
   [::rpc.doc/routes schema:routes]
   [::oidc/routes schema:routes]
   [::assets/routes schema:routes]
   [::debug/routes schema:routes]
   [::mtx/routes schema:routes]
   [::awsns/routes schema:routes]
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
                      [mw/params]
                      [mw/format-response]
                      [mw/parse-request]
                      [mw/errors errors/handle]
                      [session/soft-auth cfg]
                      [actoken/soft-auth cfg]
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
