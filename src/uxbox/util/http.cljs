;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.http
  "A http client with rx streams interface."
  (:refer-clojure :exclude [get])
  (:require [beicon.core :as rx]
            [goog.events :as events]
            [clojure.string :as str])
  (:import [goog.net ErrorCode EventType]
           [goog.net.XhrIo ResponseType]
           [goog.net XhrIo]
           [goog.Uri QueryData]
           [goog Uri]))

(defn translate-method
  [method]
  (case method
    :head    "HEAD"
    :options "OPTIONS"
    :get     "GET"
    :post    "POST"
    :put     "PUT"
    :patch   "PATCH"
    :delete  "DELETE"
    :trace   "TRACE"))

(defn- normalize-headers
  [headers]
  (reduce-kv (fn [acc k v]
               (assoc acc (str/lower-case k) v))
             {} (js->clj headers)))

(defn- translate-error-code
  [code]
  (condp = code
    ErrorCode.TIMEOUT    :timeout
    ErrorCode.EXCEPTION  :exception
    ErrorCode.HTTP_ERROR :http
    ErrorCode.ABORT      :abort))

(defn- translate-response-type
  [type]
  (println "translate-response-type" type)
  (case type
    :text ResponseType.TEXT
    :blob ResponseType.BLOB
    ResponseType.DEFAULT))

(defn- create-url
  [url qs qp]
  (let [uri (Uri. url)]
    (when qs (.setQuery uri qs))
    (when qp
      (let [dt (.createFromMap QueryData (clj->js  qp))]
        (.setQueryData uri dt)))
    (.toString uri)))

(defn- fetch
  [{:keys [method url query-string query-params headers body] :as request}
   {:keys [timeout credentials? response-type]
    :or {timeout 0 credentials? false response-type :text}}]
  (println "fetch$1" url)
  (let [uri (create-url url query-string query-params)
        headers (if headers (clj->js headers) #js {})
        method (translate-method method)
        xhr (doto (XhrIo.)
              (.setResponseType (translate-response-type response-type))
              (.setWithCredentials credentials?)
              (.setTimeoutInterval timeout))]
    (rx/create
     (fn [sink]
       (letfn [(on-complete [event]
                 (if (or (= (.getLastErrorCode xhr) ErrorCode.HTTP_ERROR)
                         (.isSuccess xhr))
                   (sink {:status (.getStatus xhr)
                          :body (.getResponse xhr)
                          :headers (normalize-headers
                                    (.getResponseHeaders xhr))})
                   (sink (let [type (-> (.getLastErrorCode xhr)
                                        (translate-error-code))
                               message (.getLastError xhr)]
                           (ex-info message {:type type})))))]

         (events/listen xhr EventType.COMPLETE on-complete)
         (.send xhr uri method body headers)
         #(.abort xhr))))))

(defn success?
  [{:keys [status]}]
  (<= 200 status 299))

(defn send!
  ([request]
   (send! request nil))
  ([request options]
   (fetch request options)))
