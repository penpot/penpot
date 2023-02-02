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
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw]
   [yetti.request :as yrq]
   [yetti.response :as yrs])
  (:import
   com.fasterxml.jackson.core.JsonParseException
   com.fasterxml.jackson.core.io.JsonEOFException
   io.undertow.server.RequestTooBigException
   java.io.OutputStream))

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
                (with-open [is (yrq/body request)]
                  (let [params (t/read! (t/reader is))]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                (str/starts-with? header "application/json")
                (with-open [is (yrq/body request)]
                  (let [params (json/decode is json-mapper)]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                :else
                request)))

          (handle-error [raise cause]
            (cond
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
      (let [request (ex/try! (process-request request))]
        (if (ex/exception? request)
          (if (ex/runtime-exception? request)
            (handle-error raise (or (ex-cause request) request))
            (handle-error raise request))
          (handler request respond raise))))))

(def parse-request
  {:name ::parse-request
   :compile (constantly wrap-parse-request)})

(defn buffered-output-stream
  "Returns a buffered output stream that ignores flush calls. This is
  needed because transit-java calls flush very aggresivelly on each
  object write."
  [^java.io.OutputStream os ^long chunk-size]
  (proxy [java.io.BufferedOutputStream] [os (int chunk-size)]
    ;; Explicitly do not forward flush
    (flush [])
    (close []
      (proxy-super flush)
      (proxy-super close))))

(def ^:const buffer-size (:xnio/buffer-size yt/defaults))

(defn wrap-format-response
  [handler]
  (letfn [(transit-streamable-body [data opts]
            (reify yrs/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try
                  (with-open [bos (buffered-output-stream output-stream buffer-size)]
                    (let [tw (t/writer bos opts)]
                      (t/write! tw data)))

                  (catch java.io.IOException _cause
                    ;; Do nothing, EOF means client closes connection abruptly
                    nil)
                  (catch Throwable cause
                    (l/warn :hint "unexpected error on encoding response"
                            :cause cause))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (json-streamable-body [data]
            (reify yrs/StreamableResponseBody
              (-write-body-to-stream [_ _ output-stream]
                (try

                  (with-open [bos (buffered-output-stream output-stream buffer-size)]
                    (json/write! bos data json-mapper))

                  (catch java.io.IOException _cause
                    ;; Do nothing, EOF means client closes connection abruptly
                    nil)
                  (catch Throwable cause
                    (l/warn :hint "unexpected error on encoding response"
                            :cause cause))
                  (finally
                    (.close ^OutputStream output-stream))))))

          (format-response-with-json [response _]
            (let [body (yrs/body response)]
              (if (or (boolean? body) (coll? body))
                (-> response
                    (update :headers assoc "content-type" "application/json")
                    (assoc :body (json-streamable-body body)))
                response)))

          (format-response-with-transit [response request]
            (let [body (yrs/body response)]
              (if (or (boolean? body) (coll? body))
                (let [qs   (yrq/query request)
                      opts (if (or (contains? cf/flags :transit-readable-response)
                                   (str/includes? qs "transit_verbose"))
                             {:type :json-verbose}
                             {:type :json})]
                  (-> response
                      (update :headers assoc "content-type" "application/transit+json")
                      (assoc :body (transit-streamable-body body opts))))
                response)))

          (format-response [response request]
            (let [accept (yrq/get-header request "accept")]
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
                 (let [response (process-response response request)]
                   (respond response)))
               raise))))

(def format-response
  {:name ::format-response
   :compile (constantly wrap-format-response)})

(defn wrap-errors
  [handler on-error]
  (fn [request respond _]
    (handler request respond (fn [cause]
                               (-> cause (on-error request) respond)))))

(def errors
  {:name ::errors
   :compile (constantly wrap-errors)})

(defn wrap-cors
  [handler]
  (if-not (contains? cf/flags :cors)
    handler
    (letfn [(add-headers [headers request]
              (let [origin (yrq/get-header request "origin")]
                (-> headers
                    (assoc "access-control-allow-origin" origin)
                    (assoc "access-control-allow-methods" "GET,POST,DELETE,OPTIONS,PUT,HEAD,PATCH")
                    (assoc "access-control-allow-credentials" "true")
                    (assoc "access-control-expose-headers" "x-requested-with, content-type, cookie")
                    (assoc "access-control-allow-headers" "x-frontend-version, content-type, accept, x-requested-width"))))

            (update-response [response request]
              (update response :headers add-headers request))]

      (fn [request respond raise]
        (if (= (yrq/method request) :options)
          (-> (yrs/response 200)
              (update-response request)
              (respond))
          (handler request
                   (fn [response]
                     (respond (update-response response request)))
                   raise))))))

(def cors
  {:name ::cors
   :compile (constantly wrap-cors)})

(defn compile-restrict-methods
  [data _]
  (when-let [allowed (:allowed-methods data)]
    (fn [handler]
      (fn [request respond raise]
        (let [method (yrq/method request)]
          (if (contains? allowed method)
            (handler request respond raise)
            (respond (yrs/response 405))))))))

(def restrict-methods
  {:name ::restrict-methods
   :compile compile-restrict-methods})

(def with-dispatch
  {:name ::with-dispatch
   :compile
   (fn [& _]
     (fn [handler executor]
       (fn [request respond raise]
         (-> (px/submit! executor #(handler request))
             (p/bind p/wrap)
             (p/then respond)
             (p/catch raise)))))})

(def with-config
  {:name ::with-config
   :compile
   (fn [& _]
     (fn [handler config]
       (fn
         ([request] (handler config request))
         ([request respond raise] (handler config request respond raise)))))})
