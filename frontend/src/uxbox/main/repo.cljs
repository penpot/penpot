;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [uxbox.config :refer [url]]
   [uxbox.util.http :as http]
   [uxbox.util.storage :refer [storage]]
   [uxbox.util.transit :as t])
  (:import [goog.Uri QueryData]))

;; --- Low Level API

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

(def ^:private +headers+
  {"content-type" "application/transit+json"})

(defn- encode-query
  [params]
  (let [data (QueryData.)]
    (.extend data (clj->js params))
    (.toString data)))

(defn impl-send
  [{:keys [body headers auth method query url response-type]
    :or {auth true response-type :text}}]
  (let [headers (merge {"Accept" "application/transit+json,*/*"}
                       (when (map? body) +headers+)
                       headers)
        request {:method method
                 :url url
                 :headers headers
                 :query-string (when query (encode-query query))
                 :body (if (map? body) (t/encode body) body)}
        options {:response-type response-type
                 :credentials? true}]
    (http/send! request options)))

(defn send!
  [request]
  (->> (impl-send request)
       (rx/map conditional-decode)
       (rx/mapcat handle-http-status)))

;; --- High Level API

(defn- handle-response
  [response]
  ;; (prn "handle-response1" response)
  (cond
    (http/success? response)
    (rx/of (:body response))

    (http/client-error? response)
    (rx/throw (:body response))

    :else
    (rx/throw {:type :unexpected
               :code (:error response)})))

(defn send-query!
  [id params]
  (let [url (str url "/api/w/query/" (name id))]
    (->> (impl-send {:method :get :url url :query params})
         (rx/map conditional-decode)
         (rx/mapcat handle-response))))

(defn send-mutation!
  [id params]
  (let [url (str url "/api/w/mutation/" (name id))]
    (->> (impl-send {:method :post :url url :body params})
         (rx/map conditional-decode)
         (rx/mapcat handle-response))))

(defn- dispatch
  [& args]
  (first args))

(defmulti query dispatch)
(defmulti mutation dispatch)

(defmethod query :default
  [id params]
  (send-query! id params))

(defmethod mutation :default
  [id params]
  (send-mutation! id params))

(defn query!
  ([id] (query id {}))
  ([id params] (query id params)))

(defn mutation!
  ([id] (mutation id {}))
  ([id params] (mutation id params)))

(defmethod mutation :create-image
  [id params]
  (let [form (js/FormData.)]
    (run! (fn [[key val]]
            (.append form (name key) val))
          (seq params))
    (send-mutation! id form)))

(defmethod mutation :login
  [id params]
  (let [url (str url "/api/login")]
    (->> (impl-send {:method :post :url url :body params})
         (rx/map conditional-decode)
         (rx/mapcat handle-response))))

(defmethod mutation :logout
  [id params]
  (let [url (str url "/api/logout")]
    (->> (impl-send {:method :post :url url :body params :auth false})
         (rx/map conditional-decode)
         (rx/mapcat handle-response))))

(def client-error? http/client-error?)
(def server-error? http/server-error?)
