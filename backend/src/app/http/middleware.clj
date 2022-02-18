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
   [ring.core.protocols :as rp]
   [ring.middleware.cookies :refer [wrap-cookies]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]
   [yetti.adapter :as yt]))

(defn wrap-server-timing
  [handler]
  (letfn [(get-age [start]
            (float (/ (- (System/nanoTime) start) 1000000000)))

          (update-headers [headers start]
            (assoc headers "Server-Timing" (str "total;dur=" (get-age start))))]

    (fn [request respond raise]
      (let [start (System/nanoTime)]
        (handler request #(respond (update % :headers update-headers start)) raise)))))

(defn wrap-parse-request-body
  [handler]
  (letfn [(parse-transit [body]
            (let [reader (t/reader body)]
              (t/read! reader)))

          (parse-json [body]
            (json/read body))

          (handle-request [{:keys [headers body] :as request}]
            (let [ctype (get headers "content-type")]
              (case ctype
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

          (handle-exception [cause]
            (let [data {:type :validation
                        :code :unable-to-parse-request-body
                        :hint "malformed params"}]
              (l/error :hint (ex-message cause) :cause cause)
              {:status 400
               :headers {"content-type" "application/transit+json"}
               :body (t/encode-str data {:type :json-verbose})}))]

    (fn [request respond raise]
      (try
        (let [request (handle-request request)]
          (handler request respond raise))
        (catch Exception cause
          (respond (handle-exception cause)))))))

(def parse-request-body
  {:name ::parse-request-body
   :compile (constantly wrap-parse-request-body)})

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

(def ^:const buffer-size (:http/output-buffer-size yt/base-defaults))

(defn wrap-format-response-body
  [handler]
  (letfn [(transit-streamable-body [data opts]
            (reify rp/StreamableResponseBody
              (write-body-to-stream [_ _ output-stream]
                ;; Use the same buffer as jetty output buffer size
                (try
                  (with-open [bos (buffered-output-stream output-stream buffer-size)]
                    (let [tw (t/writer bos opts)]
                      (t/write! tw data)))
                  (catch org.eclipse.jetty.io.EofException _cause
                    ;; Do nothing, EOF means client closes connection abruptly
                    nil)
                  (catch Throwable cause
                    (l/warn :hint "unexpected error on encoding response"
                            :cause cause))))))

          (impl-format-response-body [response {:keys [query-params] :as request}]
            (let [body   (:body response)
                  opts   {:type (if (contains? query-params "transit_verbose") :json-verbose :json)}]
              (cond
                (:ws response)
                response

                (coll? body)
                (-> response
                    (update :headers assoc "content-type" "application/transit+json")
                    (assoc :body (transit-streamable-body body opts)))

                (nil? body)
                (assoc response :status 204 :body "")

                :else
                response)))

          (handle-response [response request]
            (cond-> response
              (map? response) (impl-format-response-body request)))]

    (fn [request respond raise]
      (handler request
               (fn [response]
                 (respond (handle-response response request)))
               raise))))

(def format-response-body
  {:name ::format-response-body
   :compile (constantly wrap-format-response-body)})

(defn wrap-errors
  [handler on-error]
  (fn [request respond _]
    (handler request respond (fn [cause]
                               (-> cause (on-error request) respond)))))

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

(defn wrap-cors
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
      (fn [request respond raise]
        (if (= (:request-method request) :options)
          (-> {:status 200 :body ""}
              (add-cors-headers request)
              (respond))
          (handler request
                   (fn [response]
                     (respond (add-cors-headers response request)))
                   raise))))))

(def cors
  {:name ::cors
   :compile (constantly wrap-cors)})
