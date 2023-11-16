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
   [ring.request :as rreq]
   [ring.response :as rres]
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw])
  (:import
   com.fasterxml.jackson.core.JsonParseException
   com.fasterxml.jackson.core.io.JsonEOFException
   com.fasterxml.jackson.databind.exc.MismatchedInputException
   io.undertow.server.RequestTooBigException
   java.io.InputStream
   java.io.OutputStream))

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
            (let [header (rreq/get-header request "content-type")]
              (cond
                (str/starts-with? header "application/transit+json")
                (with-open [^InputStream is (rreq/body request)]
                  (let [params (t/read! (t/reader is))]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                (str/starts-with? header "application/json")
                (with-open [^InputStream is (rreq/body request)]
                  (let [params (json/decode is json-mapper)]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                :else
                request)))

          (handle-error [cause]
            (cond
              (instance? RuntimeException cause)
              (if-let [cause (ex-cause cause)]
                (handle-error cause)
                (throw cause))

              (instance? RequestTooBigException cause)
              (ex/raise :type :validation
                        :code :request-body-too-large
                        :hint (ex-message cause))

              (or (instance? JsonEOFException cause)
                  (instance? JsonParseException cause)
                  (instance? MismatchedInputException cause))
              (ex/raise :type :validation
                        :code :malformed-json
                        :hint (ex-message cause)
                        :cause cause)

              :else
              (throw cause)))]

    (fn [request]
      (if (= (rreq/method request) :post)
        (let [request (ex/try! (process-request request))]
          (if (ex/exception? request)
            (handle-error request)
            (handler request)))
        (handler request)))))

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
            (reify rres/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                    (let [tw (t/writer bos opts)]
                      (t/write! tw data)))
                  (catch java.io.IOException _)
                  (catch Throwable cause
                    (binding [l/*context* {:value data}]
                      (l/error :hint "unexpected error on encoding response"
                               :cause cause)))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (json-streamable-body [data]
            (reify rres/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                    (json/write! bos data json-mapper))

                  (catch java.io.IOException _)
                  (catch Throwable cause
                    (binding [l/*context* {:value data}]
                      (l/error :hint "unexpected error on encoding response"
                               :cause cause)))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (format-response-with-json [response _]
            (let [body (::rres/body response)]
              (if (or (boolean? body) (coll? body))
                (-> response
                    (update ::rres/headers assoc "content-type" "application/json")
                    (assoc ::rres/body (json-streamable-body body)))
                response)))

          (format-response-with-transit [response request]
            (let [body (::rres/body response)]
              (if (or (boolean? body) (coll? body))
                (let [qs   (rreq/query request)
                      opts (if (or (contains? cf/flags :transit-readable-response)
                                   (str/includes? qs "transit_verbose"))
                             {:type :json-verbose}
                             {:type :json})]
                  (-> response
                      (update ::rres/headers assoc "content-type" "application/transit+json")
                      (assoc ::rres/body (transit-streamable-body body opts))))
                response)))

          (format-from-params [{:keys [query-params] :as request}]
            (and (= "json" (get query-params :_fmt))
                 "application/json"))

          (format-response [response request]
            (let [accept (or (format-from-params request)
                             (rreq/get-header request "accept"))]
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

    (fn [request]
      (let [response (handler request)]
        (process-response response request)))))

(def format-response
  {:name ::format-response
   :compile (constantly wrap-format-response)})

(defn wrap-errors
  [handler on-error]
  (fn [request]
    (try
      (handler request)
      (catch Throwable cause
        (on-error cause request)))))

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
    (let [response (if (= (rreq/method request) :options)
                     {::rres/status 200}
                     (handler request))
          origin   (rreq/get-header request "origin")]
      (update response ::rres/headers with-cors-headers origin))))

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
         (fn [request]
           (let [method (rreq/method request)]
             (if (contains? allowed method)
               (handler request)
               {::rres/status 405}))))))})
