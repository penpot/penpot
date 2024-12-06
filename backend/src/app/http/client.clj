;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.client
  "Http client abstraction layer."
  (:require
   [app.common.schema :as sm]
   [integrant.core :as ig]
   [java-http-clj.core :as http]
   [promesa.core :as p])
  (:import
   java.net.http.HttpClient))

(defn client?
  [o]
  (instance? HttpClient o))

(sm/register!
 {:type ::client
  :pred client?})

(defmethod ig/init-key ::client
  [_ _]
  (http/build-client {:connect-timeout 30000 ;; 10s
                      :follow-redirects :always}))

(defn send!
  ([client req] (send! client req {}))
  ([client req {:keys [response-type sync?] :or {response-type :string sync? false}}]
   (assert (client? client) "expected valid http client")
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
   (let [client  (resolve-client cfg-or-client)
         request (update request :uri str)]
     (send! client request {:sync? true})))
  ([cfg-or-client request options]
   (let [client  (resolve-client cfg-or-client)
         request (update request :uri str)]
     (send! client request (merge {:sync? true} options)))))
