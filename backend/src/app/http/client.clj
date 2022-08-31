;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.client
  "Http client abstraction layer."
  (:require
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [java-http-clj.core :as http]))

(s/def ::client fn?)

(defmethod ig/pre-init-spec :app.http/client [_]
  (s/keys :req-un [::wrk/executor]))

(defmethod ig/init-key :app.http/client
  [_ {:keys [executor] :as cfg}]
  (let [client (http/build-client {:executor executor
                                   :connect-timeout 30000 ;; 10s
                                   :follow-redirects :always})]
    (with-meta
      (fn send
        ([req] (send req {}))
        ([req {:keys [response-type sync?] :or {response-type :string sync? false}}]
         (if sync?
           (http/send req {:client client :as response-type})
           (http/send-async req {:client client :as response-type}))))
      {::client client})))

(defn req!
  "A convencience toplevel function for gradual migration to a new API
  convention."
  ([client request]
   (client request))
  ([client request options]
   (client request options)))
