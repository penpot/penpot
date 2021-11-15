;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.http
  "A http client with rx streams interface."
  (:require
   [app.common.data :as d]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cfg]
   [app.util.cache :as c]
   [app.util.globals :as globals]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defprotocol IBodyData
  "A helper for define body data with the appropriate headers."
  (-update-headers [_ headers])
  (-get-body-data [_]))

(extend-protocol IBodyData
  globals/FormData
  (-get-body-data [it] it)
  (-update-headers [it headers]
    (dissoc headers "content-type" "Content-Type"))

  default
  (-get-body-data [it] it)
  (-update-headers [it headers] headers))

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

(defn parse-headers
  [headers]
  (into {} (map vec) (seq (.entries ^js headers))))

(def default-headers
  {"x-frontend-version" (:full @cfg/version)})

(defn fetch
  [{:keys [method uri query headers body mode omit-default-headers credentials]
    :or {mode :cors
         headers {}
         credentials "same-origin"}}]
  (rx/Observable.create
   (fn [subscriber]
     (let [controller    (js/AbortController.)
           signal        (.-signal ^js controller)
           unsubscribed? (volatile! false)
           abortable?    (volatile! true)
           query         (cond
                           (string? query) query
                           (map? query)    (u/map->query-string query)
                           :else nil)
           uri           (cond-> uri
                           (string? uri) (u/uri)
                           (some? query) (assoc :query query))

           headers       (cond-> headers
                           (not omit-default-headers)
                           (d/merge default-headers))

           headers       (-update-headers body headers)

           body          (-get-body-data body)

           params        #js {:method (translate-method method)
                              :headers (clj->js headers)
                              :body body
                              :mode (d/name mode)
                              :redirect "follow"
                              :credentials credentials
                              :referrerPolicy "no-referrer"
                              :signal signal}]
       (-> (js/fetch (str uri) params)
           (p/then (fn [response]
                     (vreset! abortable? false)
                     (.next ^js subscriber response)
                     (.complete ^js subscriber)))
           (p/catch (fn [err]
                      (vreset! abortable? false)
                      (when-not @unsubscribed?
                        (.error ^js subscriber err)))))
       (fn []
         (vreset! unsubscribed? true)
         (when @abortable?
           (.abort ^js controller)))))))

(defn send!
  [{:keys [response-type] :or {response-type :text} :as params}]
  (letfn [(on-response [response]
            (let [body (case response-type
                         :json (.json ^js response)
                         :text (.text ^js response)
                         :blob (.blob ^js response))]
              (->> (rx/from body)
                   (rx/map (fn [body]
                             {::response response
                              :status    (.-status ^js response)
                              :headers   (parse-headers (.-headers ^js response))
                              :body      body})))))]
    (->> (fetch params)
         (rx/mapcat on-response))))

(defn form-data
  [data]
  (letfn [(append [form k v]
            (if (list? v)
              (.append form (name k) (first v) (second v))
              (.append form (name k) v))
            form)]
    (reduce-kv append (js/FormData.) data)))

(defn transit-data
  [data]
  (reify IBodyData
    (-get-body-data [_] (t/encode-str data))
    (-update-headers [_ headers]
      (assoc headers "content-type" "application/transit+json"))))

(defn conditional-decode-transit
  [{:keys [body headers] :as response}]
  (let [contenttype (get headers "content-type")]
    (if (and (str/starts-with? contenttype "application/transit+json")
             (pos? (count body)))
      (assoc response :body (t/decode-str body))
      response)))

(defn success?
  [{:keys [status]}]
  (<= 200 status 299))

(defn server-error?
  [{:keys [status]}]
  (<= 500 status 599))

(defn client-error?
  [{:keys [status]}]
  (<= 400 status 499))

(defn as-promise
  [observable]
  (p/create
   (fn [resolve reject]
     (->> (rx/take 1 observable)
          (rx/subs resolve reject)))))

(defn fetch-data-uri [uri]
  (c/with-cache {:key uri :max-age (dt/duration {:hours 4})}
    (->> (send! {:method :get
                 :uri uri
                 :response-type :blob
                 :omit-default-headers true})
         (rx/filter #(= 200 (:status %)))
         (rx/map :body)
         (rx/mapcat wapi/read-file-as-data-url)
         (rx/map #(hash-map uri %)))))

(defn fetch-text [url]
  (c/with-cache {:key url :max-age (dt/duration {:hours 4})}
    (->> (send!
          {:method :get
           :mode :cors
           :omit-default-headers true
           :uri url
           :response-type :text})
         (rx/map :body))))
