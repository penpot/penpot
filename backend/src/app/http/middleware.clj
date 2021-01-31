;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http.middleware
  (:require
   [app.metrics :as mtx]
   [app.util.json :as json]
   [app.util.transit :as t]
   [clojure.java.io :as io]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]))

(defn wrap-server-timing
  [handler]
  (let [seconds-from #(float (/ (- (System/nanoTime) %) 1000000000))]
    (fn [request]
      (let [start    (System/nanoTime)
            response (handler request)]
        (update response :headers
                (fn [headers]
                  (assoc headers "Server-Timing" (str "total;dur=" (seconds-from start)))))))))

(defn wrap-parse-request-body
  [handler]
  (letfn [(parse-transit [body]
            (let [reader (t/reader body)]
              (t/read! reader)))

          (parse-json [body]
            (let [reader (io/reader body)]
              (json/read reader)))

          (parse [type body]
            (try
              (case type
                :json (parse-json body)
                :transit (parse-transit body))
              (catch Exception e
                (let [data {:type :parse
                            :hint "unable to parse request body"
                            :message (ex-message e)}]
                  {:status 400
                   :headers {"content-type" "application/transit+json"}
                   :body (t/encode-str data {:type :json-verbose})}))))]

    (fn [{:keys [headers body] :as request}]
      (let [ctype (get headers "content-type")]
        (handler
         (case ctype
           "application/transit+json"
           (let [params (parse :transit body)]
             (-> request
                 (assoc :body-params params)
                 (update :params merge params)))

           "application/json"
           (let [params (parse :json body)]
             (-> request
                 (assoc :body-params params)
                 (update :params merge params)))

           request))))))

(def parse-request-body
  {:name ::parse-request-body
   :compile (constantly wrap-parse-request-body)})

(defn- impl-format-response-body
  [response]
  (let [body (:body response)
        type :json-verbose]
    (cond
      (coll? body)
      (-> response
          (assoc :body (t/encode body {:type type}))
          (update :headers assoc
                  "content-type"
                  "application/transit+json"))

      (nil? body)
      (assoc response :status 204 :body "")

      :else
      response)))

(defn- wrap-format-response-body
  [handler]
  (fn [request]
    (let [response (handler request)]
      (cond-> response
        (map? response) (impl-format-response-body)))))

(def format-response-body
  {:name ::format-response-body
   :compile (constantly wrap-format-response-body)})

(defn wrap-errors
  [handler on-error]
  (fn [request]
    (try
      (handler request)
      (catch Throwable e
        (on-error e request)))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

(def metrics
  {:name ::metrics
   :wrap (fn [handler]
           (mtx/wrap-counter handler {:id "http__requests_counter"
                                      :help "Absolute http requests counter."}))})


(def cookies
  {:name ::cookies
   :compile (constantly wrap-cookies)})

(def params
  {:name ::params
   :compile (constantly wrap-params)})

(def multipart-params
  {:name ::multipart-params
   :compile (constantly wrap-multipart-params)})

(def keyword-params
  {:name ::keyword-params
   :compile (constantly wrap-keyword-params)})

(def server-timing
  {:name ::server-timing
   :compile (constantly wrap-server-timing)})
