;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.client
  "Http client abstraction layer."
  (:require
   [app.common.spec :as us]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [java-http-clj.core :as http]
   [promesa.core :as p])
  (:import
   java.net.http.HttpClient))

(s/def ::client #(instance? HttpClient %))
(s/def ::client-holder
  (s/keys :req [::client]))

(defmethod ig/pre-init-spec ::client [_]
  (s/keys :req [::wrk/executor]))

(defmethod ig/init-key ::client
  [_ {:keys [::wrk/executor] :as cfg}]
  (http/build-client {:executor executor
                      :connect-timeout 30000 ;; 10s
                      :follow-redirects :always}))

(defn send!
  ([client req] (send! client req {}))
  ([client req {:keys [response-type sync?] :or {response-type :string sync? false}}]
   (us/assert! ::client client)
   (if sync?
     (http/send req {:client client :as response-type})
     (try
       (http/send-async req {:client client :as response-type})
       (catch Throwable cause
         (p/rejected cause))))))

(defn- resolve-client
  [params]
  (cond
    (instance? HttpClient params)
    params

    (map? params)
    (resolve-client (::client params))

    :else
    (throw (UnsupportedOperationException. "invalid arguments"))))

(defn req!
  "A convencience toplevel function for gradual migration to a new API
  convention."
  ([cfg-or-client request]
   (let [client (resolve-client cfg-or-client)]
     (send! client request {})))
  ([cfg-or-client request options]
   (let [client (resolve-client cfg-or-client)]
     (send! client request options))))

