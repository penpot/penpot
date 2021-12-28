;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.middleware
  (:require
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.util.json :as json]
   [buddy.core.codecs :as bc]
   [buddy.core.hash :as bh]
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
            (json/read body))]
    (fn [{:keys [headers body] :as request}]
      (try
        (let [ctype (get headers "content-type")]
          (handler (case ctype
                     "application/transit+json"
                     (let [params (parse-transit body)]
                       (-> request
                           (assoc :body-params params)
                           (update :params merge params)))

                     "application/json"
                     (let [params (parse-json body)]
                       (-> request
                           (assoc :body-params params)
                           (update :params merge params)))

                     request)))
        (catch Exception e
          (let [data {:type :validation
                      :code :unable-to-parse-request-body
                      :hint "malformed params"}]
            (l/error :hint (ex-message e) :cause e)
            {:status 400
             :headers {"content-type" "application/transit+json"}
             :body (t/encode-str data {:type :json-verbose})}))))))

(def parse-request-body
  {:name ::parse-request-body
   :compile (constantly wrap-parse-request-body)})

(defn- impl-format-response-body
  [response request]
  (let [body   (:body response)
        params (:query-params request)
        opts   {:type (if (contains? params "transit_verbose") :json-verbose :json)}]
    (cond
      (:ws response)
      response

      (coll? body)
      (-> response
          (update :headers assoc "content-type" "application/transit+json")
          (assoc :body (t/encode body opts)))

      (nil? body)
      (assoc response :status 204 :body "")

      :else
      response)))

(defn- wrap-format-response-body
  [handler]
  (fn [request]
    (let [response (handler request)]
      (cond-> response
        (map? response) (impl-format-response-body request)))))

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

(defn wrap-etag
  [handler]
  (letfn [(generate-etag [{:keys [body] :as response}]
            (str "W/\"" (-> body bh/blake2b-128 bc/bytes->hex) "\""))
          (get-match [{:keys [headers] :as request}]
            (get headers "if-none-match"))]
    (fn [request]
      (let [response (handler request)]
        (if (= :get (:request-method request))
          (let [etag     (generate-etag response)
                match    (get-match request)
                response (update response :headers #(assoc % "ETag" etag))]
            (cond-> response
              (and (string? match)
                   (= :get (:request-method request))
                   (= etag match))
              (-> response
                  (assoc :body "")
                  (assoc :status 304))))
          response)))))

(def etag
  {:name ::etag
   :compile (constantly wrap-etag)})

(defn activity-logger
  [handler]
  (let [logger "penpot.profile-activity"]
    (fn [{:keys [headers] :as request}]
      (let [ip-addr    (get headers "x-forwarded-for")
            profile-id (:profile-id request)
            qstring    (:query-string request)]
        (l/info ::l/async true
                ::l/logger logger
                :ip-addr ip-addr
                :profile-id profile-id
                :uri (str (:uri request) (when qstring (str "?" qstring)))
                :method (name (:request-method request)))
        (handler request)))))

(defn- wrap-cors
  [handler]
  (if-not (contains? cf/flags :cors)
    handler
    (letfn [(add-cors-headers [response request]
              (-> response
                  (update
                   :headers
                   (fn [headers]
                     (-> headers
                         (assoc "access-control-allow-origin" (get-in request [:headers "origin"]))
                         (assoc "access-control-allow-methods" "GET,POST,DELETE,OPTIONS,PUT,HEAD,PATCH")
                         (assoc "access-control-allow-credentials" "true")
                         (assoc "access-control-expose-headers" "x-requested-with, content-type, cookie")
                         (assoc "access-control-allow-headers" "x-frontend-version, content-type, accept, x-requested-width"))))))]
      (fn [request]
        (if (= (:request-method request) :options)
          (-> {:status 200 :body ""}
              (add-cors-headers request))
          (let [response (handler request)]
            (add-cors-headers response request)))))))

(def cors
  {:name ::cors
   :compile (constantly wrap-cors)})
