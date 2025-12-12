;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.middleware
  (:require
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as-alias sm]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.http :as-alias http]
   [app.http.errors :as errors]
   [app.tokens :as tokens]
   [app.util.pointer-map :as pmap]
   [buddy.core.codecs :as bc]
   [cuerdas.core :as str]
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw]
   [yetti.request :as yreq]
   [yetti.response :as yres])
  (:import
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

(defn- get-reader
  ^java.io.BufferedReader
  [request]
  (let [^InputStream body (yreq/body request)]
    (java.io.BufferedReader.
     (java.io.InputStreamReader. body))))

(defn wrap-parse-request
  [handler]
  (letfn [(process-request [request]
            (let [header (yreq/get-header request "content-type")]
              (cond
                (str/starts-with? header "application/transit+json")
                (with-open [^InputStream is (yreq/body request)]
                  (let [params (t/read! (t/reader is))]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                (str/starts-with? header "application/json")
                (with-open [reader (get-reader request)]
                  (let [params (json/read reader :key-fn json/read-kebab-key)]
                    (-> request
                        (assoc :body-params params)
                        (update :params merge params))))

                :else
                request)))

          (handle-error [cause request]
            (cond
              (instance? RuntimeException cause)
              (if-let [cause (ex-cause cause)]
                (handle-error cause request)
                (errors/handle cause request))

              (instance? RequestTooBigException cause)
              (ex/raise :type :validation
                        :code :request-body-too-large
                        :hint (ex-message cause))

              (instance? java.io.EOFException cause)
              (ex/raise :type :validation
                        :code :malformed-json
                        :hint (ex-message cause)
                        :cause cause)

              :else
              (errors/handle cause request)))]

    (fn [request]
      (if (= (yreq/method request) :post)
        (try
          (-> request process-request handler)
          (catch Throwable cause
            (handle-error cause request)))
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

(defn- write-json-value
  [_ val]
  (if (pmap/pointer-map? val)
    [(pmap/get-id val) (meta val)]
    val))

(defn wrap-format-response
  [handler]
  (letfn [(transit-streamable-body [data opts _ output-stream]
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
                (.close ^OutputStream output-stream))))

          (json-streamable-body [data _ output-stream]
            (try
              (let [encode (or (-> data meta :encode/json) identity)
                    data   (encode data)]
                (with-open [^OutputStream bos (buffered-output-stream output-stream buffer-size)]
                  (with-open [^java.io.OutputStreamWriter writer (java.io.OutputStreamWriter. bos)]
                    (json/write writer data :key-fn json/write-camel-key :value-fn write-json-value))))
              (catch java.io.IOException _)
              (catch Throwable cause
                (binding [l/*context* {:value data}]
                  (l/error :hint "unexpected error on encoding response"
                           :cause cause)))
              (finally
                (.close ^OutputStream output-stream))))

          (format-response-with-json [response _]
            (let [body (::yres/body response)]
              (if (or (boolean? body) (coll? body))
                (-> response
                    (update ::yres/headers assoc "content-type" "application/json")
                    (assoc ::yres/body (yres/stream-body (partial json-streamable-body body))))
                response)))

          (format-response-with-transit [response request]
            (let [body (::yres/body response)]
              (if (or (boolean? body) (coll? body))
                (let [qs   (yreq/query request)
                      opts (if (or (contains? cf/flags :transit-readable-response)
                                   (str/includes? qs "transit_verbose"))
                             {:type :json-verbose}
                             {:type :json})]
                  (-> response
                      (update ::yres/headers assoc "content-type" "application/transit+json")
                      (assoc ::yres/body (yres/stream-body (partial transit-streamable-body body opts)))))
                response)))

          (format-from-params [{:keys [query-params] :as request}]
            (and (= "json" (get query-params :_fmt))
                 "application/json"))

          (format-response [response request]
            (let [accept (or (format-from-params request)
                             (yreq/get-header request "accept"))]
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
    (let [response (if (= (yreq/method request) :options)
                     {::yres/status 200}
                     (handler request))
          origin   (yreq/get-header request "origin")]
      (update response ::yres/headers with-cors-headers origin))))

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
           (let [method (yreq/method request)]
             (if (contains? allowed method)
               (handler request)
               {::yres/status 405}))))))})

(defn- wrap-auth
  [handler decoders]
  (let [token-re
        #"(?i)^(Token|Bearer)\s+(.*)"

        get-token-from-authorization
        (fn [request]
          (when-let [[_ token-type token] (some->> (yreq/get-header request "authorization")
                                                   (re-matches token-re))]
            (if (= "token" (str/lower token-type))
              {:type :token
               :token token}
              {:type :bearer
               :token token})))

        get-token-from-cookie
        (fn [request]
          (let [cname (cf/get :auth-token-cookie-name)
                token (some-> (yreq/get-cookie request cname) :value)]
            (when-not (str/empty? token)
              {:type :cookie
               :token token})))

        get-token
        (some-fn get-token-from-cookie get-token-from-authorization)

        process-request
        (fn [request]
          (if-let [{:keys [type token] :as auth} (get-token request)]
            (let [decode-fn (get decoders type)]
              (if (or (= type :cookie) (= type :bearer))
                (let [metadata (tokens/decode-header token)]
                  ;; NOTE: we only proceed to decode claims on new
                  ;; cookie tokens. The old cookies dont need to be
                  ;; decoded because they use the token string as ID
                  (if (and (= (:kid metadata) 1)
                           (= (:ver metadata) 1)
                           (some? decode-fn))
                    (assoc request ::http/auth-data (assoc auth
                                                           :claims (decode-fn token)
                                                           :metadata metadata))
                    (assoc request ::http/auth-data (assoc auth :metadata {:ver 0}))))

                (if decode-fn
                  (assoc request ::http/auth-data (assoc auth :claims (decode-fn token)))
                  (assoc request ::http/auth-data auth))))

            request))]

    (fn [request]
      (-> request process-request handler))))

(def auth
  {:name ::auth
   :compile (constantly wrap-auth)})

(defn- wrap-shared-key-auth
  [handler shared-key]
  (if shared-key
    (let [shared-key (if (string? shared-key)
                       shared-key
                       (bc/bytes->b64-str shared-key true))]
      (fn [request]
        (let [key (yreq/get-header request "x-shared-key")]
          (if (= key shared-key)
            (handler request)
            {::yres/status 403}))))
    (fn [_ _]
      {::yres/status 403})))

(def shared-key-auth
  {:name ::shared-key-auth
   :compile (constantly wrap-shared-key-auth)})
