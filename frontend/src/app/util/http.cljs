;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.http
  "A http client with rx streams interface."
  (:require
   [app.common.data :as d]
   [app.config :as cfg]
   [app.util.object :as obj]
   [app.util.transit :as t]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [promesa.core :as p]))

(defprotocol IBodyData
  "A helper for define body data with the appropiate headers."
  (-update-headers [_ headers])
  (-get-body-data [_]))

(extend-protocol IBodyData
  js/FormData
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
  [{:keys [method uri query headers body timeout mode]
    :or {timeout 10000 mode :cors headers {}}}]
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
           headers       (->> (d/merge headers default-headers)
                              (-update-headers body))
           body          (-get-body-data body)
           params        #js {:method (translate-method method)
                              :headers (clj->js headers)
                              :body body
                              :mode (d/name mode)
                              :redirect "follow"
                              :credentials "same-origin"
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
    (-get-body-data [_] (t/encode data))
    (-update-headers [_ headers]
      (assoc headers "content-type" "application/transit+json"))))

(defn conditional-decode-transit
  [{:keys [body headers status] :as response}]
  (let [contentype (get headers "content-type")]
    (if (and (str/starts-with? contentype "application/transit+json")
             (pos? (count body)))
      (assoc response :body (t/decode body))
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
  (p/create (fn [resolve reject]
              (->> (rx/take 1 observable)
                   (rx/subs resolve reject)))))
