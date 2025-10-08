;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.http
  "A http client with rx streams interface."
  (:require
   [app.common.data :as d]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cfg]
   [app.util.cache :as c]
   [app.util.globals :as globals]
   [app.util.perf :as perf]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defprotocol IBodyData
  "A helper for define body data with the appropriate headers."
  (-update-headers [_ headers])
  (-get-body-data [_]))

(extend-protocol IBodyData
  globals/FormData
  (-get-body-data [it] it)
  (-update-headers [_ headers]
    (dissoc headers "content-type" "Content-Type"))

  default
  (-get-body-data [it] it)
  (-update-headers [_ headers] headers))

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

(defn default-headers
  []
  {"x-frontend-version" (:full cfg/version)
   "x-client" (str "penpot-frontend/" (:full cfg/version))})

;; Storage to save the average time of the requests
(defonce network-averages
  (atom {}))

(defn fetch
  [{:keys [method uri query headers body mode omit-default-headers credentials]
    :or {mode :cors
         headers {}
         credentials "same-origin"}}]
  (rx/create
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
                           (merge (default-headers)))

           headers       (-update-headers body headers)

           body          (-get-body-data body)

           params        #js {:method (translate-method method)
                              :headers (clj->js headers)
                              :body body
                              :mode (d/name mode)
                              :redirect "follow"
                              :credentials credentials
                              :referrerPolicy "no-referrer"
                              :signal signal}

           start (perf/timestamp)]

       (-> (js/fetch (str uri) params)
           (p/then
            (fn [response]
              (vreset! abortable? false)
              (.next ^js subscriber response)
              (.complete ^js subscriber)))
           (p/catch
            (fn [err]
              (vreset! abortable? false)
              (when-not @unsubscribed?
                (.error ^js subscriber err))))
           (p/finally
             (fn []
               (let [{:keys [count average] :or {count 0 average 0}} (get @network-averages (:path uri))
                     current-time (- (perf/timestamp) start)
                     average (+ (* average (/ count (inc count)))
                                (/ current-time (inc count)))
                     count (inc count)]
                 (swap! network-averages assoc (:path uri) {:count count :average average})))))
       (fn []
         (vreset! unsubscribed? true)
         (when @abortable?
           (.abort ^js controller)))))))

(defn response->map
  [response]
  {:status  (.-status ^js response)
   :uri     (.-url ^js response)
   :headers (parse-headers (.-headers ^js response))
   :body    (.-body ^js response)
   ::response response})

(defn process-response-type
  [response-type response]
  (let [native-response (::response response)
        body            (case response-type
                          :buffer (.arrayBuffer ^js native-response)
                          :json   (.json ^js native-response)
                          :text   (.text ^js native-response)
                          :blob   (.blob ^js native-response))]
    (->> (rx/from body)
         (rx/map (fn [body]
                   (assoc response :body body))))))

(defn send!
  [{:keys [response-type] :or {response-type :text} :as params}]
  (->> (fetch params)
       (rx/map response->map)
       (rx/mapcat (partial process-response-type response-type))))

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
    (-get-body-data [_] (t/encode-str data {:type :json-verbose}))
    (-update-headers [_ headers]
      (assoc headers "content-type" "application/transit+json"))))

(defn conditional-decode-transit
  [{:keys [body headers] :as response}]
  (let [contenttype (get headers "content-type")]
    (if (and (str/starts-with? contenttype "application/transit+json")
             (string? body)
             (pos? (count body)))
      (assoc response :body (t/decode-str body))
      response)))

(defn conditional-error-decode-transit
  [{:keys [body status] :as response}]
  (if (and (>= status 400) (string? body))
    (assoc response :body (t/decode-str body))
    response))

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
          (rx/subs! resolve reject)))))

(defn fetch-data-uri
  ([uri]
   (fetch-data-uri uri false))

  ([uri throw-err?]
   (let [request-str
         (->> (send! {:method :get
                      :uri uri
                      :response-type :blob
                      :omit-default-headers true})
              (rx/tap
               (fn [resp]
                 (when (or (< (:status resp) 200) (>= (:status resp) 300))
                   (throw (js/Error. "Error fetching data uri" #js {:cause (clj->js resp)})))))

              (rx/map :body)
              (rx/mapcat wapi/read-file-as-data-url)
              (rx/map #(hash-map uri %))
              (c/with-cache {:key uri :max-age (* 1000 60 60 4)}))]

     ;; We need to check `throw-err?` after the cache is resolved otherwise we cannot cache request
     ;; with different values of throw-err. By default we throw always the exception and then we just
     ;; ignore when `throw-err?` is `true`
     (if (not throw-err?)
       (->> request-str (rx/catch #(rx/empty)))
       request-str))))

(defn fetch-text [url]
  (->> (send!
        {:method :get
         :mode :cors
         :omit-default-headers true
         :uri url
         :response-type :text})
       (rx/map :body)
       (c/with-cache {:key url :max-age (* 1000 60 60 4)})))
