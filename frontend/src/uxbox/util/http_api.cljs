;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.http-api
  "A specific customizations of http client for api access."
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [uxbox.util.http :as http]
   [uxbox.util.transit :as t]))

(defn- conditional-decode
  [{:keys [body headers] :as response}]
  (let [contentype (get headers "content-type")]
    (if (str/starts-with? contentype "application/transit+json")
      (assoc response :body (t/decode body))
      response)))

(defn- handle-http-status
  [{:keys [body status] :as response}]
  (if (http/success? response)
    (rx/of {:status status :payload body})
    (rx/throw {:status status :payload body})))

(def ^:private default-headers
  {"content-type" "application/transit+json"})

(defn- impl-send
  [{:keys [body headers auth method query uri response-type]
    :or {auth true response-type :text}}]
  (let [headers (merge {"Accept" "application/transit+json,*/*"}
                       (when (map? body) default-headers)
                       headers)
        request {:method method
                 :uri uri
                 :headers headers
                 :query query
                 :body (if (map? body)
                         (t/encode body)
                         body)}
        options {:response-type response-type
                 :credentials? auth}]
    (http/send! request options)))

(defn send!
  [request]
  (->> (impl-send request)
       (rx/map conditional-decode)))

(def success? http/success?)
(def client-error? http/client-error?)
(def server-error? http/server-error?)
