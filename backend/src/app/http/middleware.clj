;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.middleware
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.util.json :as json]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.util :as pu]
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw]
   [yetti.request :as yrq]
   [yetti.response :as yrs])
  (:import
   com.fasterxml.jackson.core.JsonParseException
   com.fasterxml.jackson.core.io.JsonEOFException
   io.undertow.server.RequestTooBigException
   java.io.OutputStream
   java.io.InputStream))

(set! *warn-on-reflection* true)

(def server-timing
  {:name ::server-timing
   :compile (constantly ymw/wrap-server-timing)})

(def params
  {:name ::params
   :compile (constantly ymw/wrap-params)})

(def ^:private json-mapper
  (json/mapper
   {:encode-key-fn str/camel
    :decode-key-fn (comp keyword str/kebab)
    :pretty true}))

(defn wrap-parse-request
  [handler]
  (letfn [(process-request [request]
            (let [header (yrq/get-header request "content-type")]
              (cond
                (str/starts-with? header "application/transit+json")
                (with-open [^InputStream is (yrq/body request)]
                  (let [params (t/read! (t/reader is))]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                (str/starts-with? header "application/json")
                (with-open [^InputStream is (yrq/body request)]
                  (let [params (json/decode is json-mapper)]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                :else
                request)))

          (handle-error [raise cause]
            (cond
              (instance? RuntimeException cause)
              (if-let [cause (ex-cause cause)]
                (handle-error raise cause)
                (raise cause))

              (instance? RequestTooBigException cause)
              (raise (ex/error :type :validation
                               :code :request-body-too-large
                               :hint (ex-message cause)))


              (or (instance? JsonEOFException cause)
                  (instance? JsonParseException cause))
              (raise (ex/error :type :validation
                               :code :malformed-json
                               :hint (ex-message cause)
                               :cause cause))
              :else
              (raise cause)))]

    (fn [request respond raise]
      (if (= (yrq/method request) :post)
        (let [request (ex/try! (process-request request))]
          (if (ex/exception? request)
            (handle-error raise request)
            (handler request respond raise)))
        (handler request respond raise)))))

(def parse-request
  {:name ::parse-request
   :compile (constantly wrap-parse-request)})

(defn buffered-output-stream
  "Returns a buffered output stream that ignores flush calls. This is
  needed because transit-java calls flush very aggresivelly on each
  object write."
  [^java.io.OutputStream os ^long chunk-size]
  (yetti.util.BufferedOutputStream. os (int chunk-size)))

(def ^:const buffer-size (:xnio/buffer-size yt/defaults))

(defn wrap-format-response
  [handler]
  (letfn [(transit-streamable-body [data opts]
            (reify yrs/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                    (let [tw (t/writer bos opts)]
                      (t/write! tw data)))
                  (catch java.io.IOException _)
                  (catch Throwable cause
                    (l/warn :hint "unexpected error on encoding response"
                            :cause cause))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (json-streamable-body [data]
            (reify yrs/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                    (json/write! bos data json-mapper))

                  (catch java.io.IOException _)
                  (catch Throwable cause
                    (l/warn :hint "unexpected error on encoding response"
                            :cause cause))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (format-response-with-json [response _]
            (let [body (::yrs/body response)]
              (if (or (boolean? body) (coll? body))
                (-> response
                    (update ::yrs/headers assoc "content-type" "application/json")
                    (assoc ::yrs/body (json-streamable-body body)))
                response)))

          (format-response-with-transit [response request]
            (let [body (::yrs/body response)]
              (if (or (boolean? body) (coll? body))
                (let [qs   (yrq/query request)
                      opts (if (or (contains? cf/flags :transit-readable-response)
                                   (str/includes? qs "transit_verbose"))
                             {:type :json-verbose}
                             {:type :json})]
                  (-> response
                      (update ::yrs/headers assoc "content-type" "application/transit+json")
                      (assoc ::yrs/body (transit-streamable-body body opts))))
                response)))

          (format-from-params [{:keys [query-params] :as request}]
            (and (= "json" (get query-params :_fmt))
                 "application/json"))

          (format-response [response request]
            (let [accept (or (format-from-params request)
                             (yrq/get-header request "accept"))]
              (cond
                (or (= accept "application/transit+json")
                    (str/includes? accept "application/transit+json"))
                (format-response-with-transit response request)

                (or (= accept "application/json")
                    (str/includes? accept "application/json"))
                (format-response-with-json response request)

                :else
                (format-response-with-transit response request))))

          (process-response [response request]
            (cond-> response
              (map? response) (format-response request)))]

    (fn [request respond raise]
      (handler request
               (fn [response]
                 (respond (process-response response request)))
               raise))))

(def format-response
  {:name ::format-response
   :compile (constantly wrap-format-response)})

(defn wrap-errors
  [handler on-error]
  (fn [request respond raise]
    (handler request respond (fn [cause]
                               (try
                                 (respond (on-error cause request))
                                 (catch Throwable cause
                                   (raise cause)))))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

(defn- with-cors-headers
  [headers origin]
  (-> headers
      (assoc "access-control-allow-origin" origin)
      (assoc "access-control-allow-methods" "GET,POST,DELETE,OPTIONS,PUT,HEAD,PATCH")
      (assoc "access-control-allow-credentials" "true")
      (assoc "access-control-expose-headers" "x-requested-with, content-type, cookie")
      (assoc "access-control-allow-headers" "x-frontend-version, content-type, accept, x-requested-width")))

(defn wrap-cors
  [handler]
  (fn [request]
    (let [response (if (= (yrq/method request) :options)
                     {::yrs/status 200}
                     (handler request))
          origin   (yrq/get-header request "origin")]
      (update response ::yrs/headers with-cors-headers origin))))

(def cors
  {:name ::cors
   :compile (fn [& _]
              (when (contains? cf/flags :cors)
                wrap-cors))})

(def restrict-methods
  {:name ::restrict-methods
   :compile
   (fn [data _]
     (when-let [allowed (:allowed-methods data)]
       (fn [handler]
         (fn [request respond raise]
           (let [method (yrq/method request)]
             (if (contains? allowed method)
               (handler request respond raise)
               (respond {::yrs/status 405})))))))})

(def with-dispatch
  {:name ::with-dispatch
   :compile
   (fn [& _]
     (fn [handler executor]
       (let [executor (px/resolve-executor executor)]
         (fn [request respond raise]
           (->> (px/submit! executor (partial handler request))
                (p/fnly (pu/handler respond raise)))))))})
