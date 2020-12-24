;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http
  (:require
   [clojure.pprint]
   [app.config :as cfg]
   [app.http.auth :as auth]
   ;; [app.http.auth.gitlab :as gitlab]
   [app.http.auth.google :as google]
   ;; [app.http.auth.ldap :as ldap]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   ;; [app.http.ws :as ws]
   [app.metrics :as mtx]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [clojure.spec.alpha :as s]
   [reitit.ring :as rr]
   [ring.adapter.jetty9 :as jetty])
  (:import
   org.eclipse.jetty.server.handler.ErrorHandler))

(defmethod ig/init-key ::server
  [_ {:keys [router ws port] :as opts}]
  (log/info "Starting http server.")
  (let [options {:port port
                 :h2c? true
                 :join? false
                 :allow-null-path-info true
                 :websockets ws}
        server  (jetty/run-jetty router options)
        handler (doto (ErrorHandler.)
                  (.setShowStacks true)
                  (.setServer server))]

    (.setErrorHandler server handler)
    server))

(defmethod ig/halt-key! ::server
  [_ server]
  (log/info "Stoping http server." server)
  (.stop server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Main Handler (Router)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare create-router)

(s/def ::rpc map?)
(s/def ::session map?)
(s/def ::metrics map?)
(s/def ::google-auth map?)
(s/def ::gitlab-auth map?)
(s/def ::ldap-auth fn?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::session ::metrics ::google-auth ::gitlab-auth]))

(defmethod ig/init-key ::router
  [_ cfg]
  (rr/ring-handler
   (create-router cfg)
   (rr/routes
    (rr/create-resource-handler {:path "/"})
    (rr/create-default-handler))))

(defn- create-router
  [{:keys [session rpc google-auth gitlab-auth metrics ldap-auth] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]
    ["/api" {:middleware [[middleware/format-response-body]
                          [middleware/parse-request-body]
                          [middleware/errors errors/handle]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/cookies]]}

     ;; ["/svg" {:post handlers/parse-svg}]

     ["/oauth"
      ["/google" {:post (:auth-handler google-auth)}]
      ["/google/callback" {:get (:callback-handler google-auth)}]

      ["/gitlab" {:post (:auth-handler gitlab-auth)}]
      ["/gitlab/callback" {:get (:callback-handler gitlab-auth)}]]

     ["/login" {:post #(auth/login-handler cfg %)}]
     ["/logout" {:post #(auth/logout-handler cfg %)}]

     ["/login-ldap" {:post ldap-auth}]

     ["/rpc" {:middleware [(:middleware session)]}
      ["/query/:type" {:get (:query-handler rpc)}]
      ["/mutation/:type" {:post (:mutation-handler rpc)}]]]]))

