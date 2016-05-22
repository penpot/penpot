;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.repo.core
  "A main interface for access to remote resources."
  (:refer-clojure :exclude [do])
  (:require [clojure.walk :as walk]
            [httpurr.client.xhr :as http]
            [httpurr.status :as http.status]
            [promesa.core :as p :include-macros true]
            [beicon.core :as rx]
            [uxbox.util.transit :as t]
            [uxbox.state :as ust])
  (:import [goog.Uri QueryData]))

(goog-define url "http://127.0.0.1:5050/api")

(defn- conditional-decode
  [{:keys [body headers] :as response}]
  (if (= (get headers "content-type") "application/transit+json")
    (assoc response :body (t/decode body))
    response))

(defn- handle-http-status
  [{:keys [body status] :as response}]
  (if (http.status/success? response)
    (rx/of {:status status :payload body})
    (rx/throw {:status status :payload body})))

(def ^:private ^:const +headers+
  {"content-type" "application/transit+json"})

(defn- auth-headers
  []
  (when-let [auth (:auth @ust/state)]
    {"authorization" (str "Token " (:token auth "no-token"))}))

(defn- encode-query
  [params]
  (let [data (QueryData.)]
    (.extend data (clj->js params))
    (.toString data)))

(defn- send!
  [{:keys [body headers auth method query url] :or {auth true} :as request}]
  (let [headers (merge {}
                       (when (map? body) +headers+)
                       headers
                       (when auth (auth-headers)))
        request {:method method
                 :url url
                 :headers headers
                 :query-string (when query (encode-query query))
                 :body (if (map? body) (t/encode body) body)}]
    (->> (http/send! request)
         (rx/from-promise)
         (rx/map conditional-decode)
         (rx/mapcat handle-http-status))))

(defmulti request
  (fn [type data] type))

(defmethod request :default
  [type data]
  (throw (ex-info (str "No implementation found for " type) {:data data})))
