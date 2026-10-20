;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.metrics :as mtx]
   [app.tokens :as tokens]
   [app.util.inet :as inet]
   [app.util.pointer-map :as pmap]
   [cuerdas.core :as str]
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw]
   [yetti.request :as yreq]
   [yetti.response :as yres])
  (:import
   io.undertow.server.RequestTooBigException
   java.io.InputStream
   java.io.OutputStream
   java.security.MessageDigest))

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

(def ^:private not-a-body
  "Marker returned by `read-body-params` when the `content-type` header names
  neither transit nor json; the request is left untouched.

  A fresh object and not a keyword: a keyword is a legal value for a decoded
  body, so identity is the only safe comparison."
  (Object.))

(defn- raise-malformed-body
  "Signals a body that the parser could not read.

  A failure while reading the body means the body does not match what the
  client declared, so it is a client error: the exception class says nothing
  here, because `clojure.data.json` signals its syntax errors with a bare
  `java.lang.Exception`."
  [request metrics content-type cause]
  ;; A single line, without the stack trace: the trace of a parse failure
  ;; hides the fields (path, peer address, content-type) that identify the
  ;; client sending the broken body.
  (l/wrn :hint "malformed request body"
         :path (yreq/path request)
         :remote-addr (inet/parse-request request)
         :content-type content-type
         :error (ex/get-hint cause))

  (mtx/run! metrics
            :id :http-malformed-requests-total
            :inc 1)

  (ex/raise :type :validation
            :code :malformed-json
            :hint "invalid JSON in request body"
            :cause cause))

(defn- read-body-params
  "Reads the request body according to the `content-type` header.

  `RequestTooBigException` keeps its own code (413) and a structured error
  raised from deeper (an `ex-info`) travels untouched; anything else is a
  malformed body."
  [request metrics content-type]
  (try
    (cond
      (str/starts-with? content-type "application/transit+json")
      (with-open [^InputStream is (yreq/body request)]
        (t/read! (t/reader is)))

      (str/starts-with? content-type "application/json")
      (with-open [reader (get-reader request)]
        (json/read reader :key-fn json/read-kebab-key))

      :else
      not-a-body)

    (catch RequestTooBigException cause
      (ex/raise :type :validation
                :code :request-body-too-large
                :hint "request body exceeds size limit"
                :cause cause))

    (catch Throwable cause
      (if (ex/error? cause)
        (throw cause)
        (raise-malformed-body request metrics content-type cause)))))

(defn wrap-parse-request
  [handler metrics]
  (letfn [(process-request [request]
            (let [header (yreq/get-header request "content-type")
                  params (read-body-params request metrics header)]
              (if (identical? params not-a-body)
                request
                (-> request
                    (assoc :body-params params)
                    (update :params merge params)))))

          ;; The specific-exception branches below (IAE,
          ;; RequestTooBigException, EOFException) raise with
          ;; `ex/raise` rather than calling `errors/handle` directly.
          ;; This is intentional: the throw is caught by the
          ;; top-level error handler in `app.http/router-handler`
          ;; (`backend/src/app/http.clj`), which routes every
          ;; uncaught exception through `errors/handle`. The
          ;; per-route `wrap-errors` middleware in the route list
          ;; is a defensive layer; correctness does not depend on
          ;; it. Raising here keeps the cond uniform with the
          ;; existing RequestTooBigException / EOFException
          ;; branches.
          (handle-error [cause request]
            (cond
              (instance? IllegalArgumentException cause)
              (ex/raise :type :validation
                        :code :malformed-json
                        :hint "invalid JSON in request body"
                        :cause cause)

              (instance? RequestTooBigException cause)
              (ex/raise :type :validation
                        :code :request-body-too-large
                        :hint "request body exceeds size limit")

              (instance? java.io.EOFException cause)
              (ex/raise :type :validation
                        :code :malformed-json
                        :hint "unexpected end of request body"
                        :cause cause)

              (instance? RuntimeException cause)
              (if-let [cause (ex-cause cause)]
                (handle-error cause request)
                (errors/handle cause request))

              :else
              (errors/handle cause request)))]

    (fn [request]
      (if (= (yreq/method request) :post)
        ;; The body is read outside the `try`: a failure there is always
        ;; raised already classified by `read-body-params`, and goes out as a
        ;; throw like the rest of the specific-exception branches above.
        (let [request (process-request request)]
          (try
            (handler request)
            (catch Throwable cause
              (handle-error cause request))))
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
  "Build CORS response headers. Only emits permissive headers when the
  request `origin` is present on the configured `allowed` allowlist;
  otherwise returns the headers unchanged except for `Vary: Origin` so
  shared caches don't leak per-origin responses."
  [headers origin allowed]
  (cond-> (assoc headers "vary" "Origin")
    (and (some? origin) (contains? allowed origin))
    (-> (assoc "access-control-allow-origin" origin)
        (assoc "access-control-allow-credentials" "true")
        (assoc "access-control-allow-methods" "GET,POST,DELETE,OPTIONS,PUT,HEAD,PATCH")
        (assoc "access-control-expose-headers" "content-type, retry-after, x-rate-limit-remaining, x-rate-limit-reset")
        (assoc "access-control-allow-headers" "x-frontend-version, x-client, content-type, accept"))))

(defn wrap-cors
  [handler allowed]
  (fn [request]
    (let [response (if (= (yreq/method request) :options)
                     {::yres/status 204}
                     (handler request))
          origin   (yreq/get-header request "origin")]
      (update response ::yres/headers with-cors-headers origin allowed))))

(def cors
  {:name ::cors
   :compile (fn [& _]
              (when (contains? cf/flags :cors)
                (let [allowed (not-empty (cf/get :allowed-origins))]
                  (if allowed
                    (fn [handler] (wrap-cors handler allowed))
                    (do
                      (l/wrn :hint (str "cors flag is enabled but :allowed-origins is empty; "
                                        "CORS middleware disabled (fail-closed). "
                                        "Configure PENPOT_ALLOWED_ORIGINS with a comma-separated list of trusted origins."))
                      nil)))))})

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
                  ;; NOTE: only current (kid=1/ver=1) cookie tokens carry
                  ;; decodable claims. Anything else is left unauthenticated.
                  (if (and (= (:kid metadata) 1)
                           (= (:ver metadata) 1)
                           (some? decode-fn))
                    (assoc request ::http/auth-data (assoc auth
                                                           :claims (decode-fn token)
                                                           :metadata metadata))
                    request))

                (if decode-fn
                  (assoc request ::http/auth-data (assoc auth :claims (decode-fn token)))
                  (assoc request ::http/auth-data auth))))

            request))]

    (fn [request]
      (-> request process-request handler))))

(def auth
  {:name ::auth
   :compile (constantly wrap-auth)})

(defn constant-time-eq?
  "Compare strings in constant time to prevent timing attacks."
  [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

(defn- wrap-shared-key-auth
  [handler keys]
  (if (seq keys)
    (fn [request]
      (if-let [[key-id key] (some-> (yreq/get-header request "x-shared-key")
                                    (str/split #"\s+" 2))]
        (let [key-id (-> key-id str/lower keyword)]
          (if (and (string? key)
                   (contains? keys key-id)
                   (constant-time-eq? key (get keys key-id)))
            (-> request
                (assoc ::http/auth-key-id key-id)
                (handler))
            {::yres/status 403}))
        {::yres/status 403}))
    (fn [_]
      {::yres/status 403})))

(def shared-key-auth
  {:name ::shared-key-auth
   :compile (constantly wrap-shared-key-auth)})
