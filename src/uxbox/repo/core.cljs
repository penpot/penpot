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

(def ^:private ^:const +uri+
  "http://127.0.0.1:5050/api")

(def ^:private +storage+
  local-storage)

(defn- conditional-decode
  [{:keys [body headers] :as response}]
  (if (= (get headers "content-type") "application/transit+json")
    (assoc response :body (t/decode body))
    response))

(defn- handle-http-status
  [response]
  (if (http.status/success? response)
    (p/resolved response)
    (p/rejected response)))

(def ^:private ^:const +headers+
  {"content-type" "application/transit+json"})

(defn auth-headers
  []
  (when-let [auth (:auth @ust/state)]
    {"authorization" (str "Token " (:token auth "no-token"))}))

(defn- send!
  [{:keys [body headers auth method] :or {auth true} :as request}]
  (let [headers (merge {}
                       (when (not= method :get) +headers+)
                       headers
                       (when auth (auth-headers)))
        request (merge (assoc request :headers headers)
                       (when body {:body (t/encode body)}))]
    (-> (http/send! request)
        (p/catch (fn [err]
                   (println "[error]:" err)
                   (throw err)))
        (p/then conditional-decode)
        (p/then handle-http-status))))

(defn- req!
  [request]
  (let [on-success #(p/resolved (:body %))
        on-error #(if (map? %)
                    (p/rejected (:body %))
                    (p/rejected %))]

    (-> (send! request)
        (p/then on-success)
        (p/catch on-error))))

(defmulti -do
  (fn [type data] type))

