;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.core
  "A main interface for access to remote resources."
  (:refer-clojure :exclude [do])
  (:require [httpurr.client.xhr :as http]
            [httpurr.status :as http.status]
            [hodgepodge.core :refer (local-storage)]
            [promesa.core :as p :include-macros true]
            [beicon.core :as rx]
            [uxbox.transit :as t]
            [uxbox.state :as ust]))

(goog-define url "http://127.0.0.1:5050/api")

(def ^:private +storage+
  local-storage)

(defn- conditional-decode
  [{:keys [body headers] :as response}]
  (if (= (get headers "content-type") "application/transit+json")
    (assoc response :body (t/decode body))
    response))

(defrecord Ok [status payload])
(defrecord ServerError [status paylpad])
(defrecord ClientError [status payload])
(defrecord NotFound [payload])

(defn- handle-http-status
  [{:keys [body status] :as response}]
  (cond
    (http.status/success? response)
    (rx/of (->Ok status body))

    (http.status/client-error? response)
    (rx/throw
     (if (= status 404)
       (->NotFound body)
       (->ClientError status body)))

    (http.status/server-error? response)
    (rx/throw (->ServerError status body))))

(def ^:private ^:const +headers+
  {"content-type" "application/transit+json"})

(defn auth-headers
  []
  (when-let [auth (:auth @ust/state)]
    {"authorization" (str "Token " (:token auth "no-token"))}))

(defn- send!
  [{:keys [body headers auth method] :or {auth true} :as request}]
  (let [headers (merge {}
                       (when body +headers+)
                       headers
                       (when auth (auth-headers)))
        request (merge (assoc request :headers headers)
                       (when body {:body (t/encode body)}))]
    (->> (http/send! request)
         (rx/from-promise)
         (rx/map conditional-decode)
         (rx/mapcat handle-http-status))))

(defmulti -do
  (fn [type data] type))

(defmethod -do :default
  [type data]
  (throw (ex-info (str "No implementation found for " type) {:data data})))
