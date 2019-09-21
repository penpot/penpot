;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.middleware
  (:require
   [clojure.spec.alpha :as s]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [reitit.ring :as rr]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.multipart :as multipart]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [struct.core :as st]
   [uxbox.config :as cfg]
   [uxbox.http.cors :refer [wrap-cors]]
   [uxbox.http.errors :as errors]
   [uxbox.http.etag :refer [wrap-etag]]
   [uxbox.http.response :as rsp]
   [uxbox.util.data :refer [normalize-attrs]]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.spec :as us]
   [uxbox.util.transit :as t]))

(extend-protocol ring.core.protocols/StreamableResponseBody
  (Class/forName "[B")
  (write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream]
      (.write out ^bytes body))))

(defn- transform-handler
  [handler]
  (fn [request respond raise]
    (try
      (let [response (handler request)]
        (if (p/promise? response)
          (-> response
              (p/then respond)
              (p/catch raise))
          (respond response)))
      (catch Exception e
        (raise e)))))

;; The middleware that transform string keys to keywords and perform
;; usability transformations.

(def ^:private normalize-params-middleware
  {:name ::normalize-params-middleware
   :wrap (fn [handler]
           (letfn [(transform-request [request key]
                     (if-let [data (get request key)]
                       (assoc request key (normalize-attrs data))
                       request))
                   (transform [request]
                     (-> request
                         (transform-request :query-params)
                         (transform-request :multipart-params)))]
             (fn
               ([request] (handler (transform request)))
               ([request respond raise]
                (try
                  (try
                    (handler (transform request) respond raise)
                    (catch Exception e
                      (raise e))))))))})

(def ^:private multipart-params-middleware
  {:name ::multipart-params-middleware
   :wrap wrap-multipart-params})

(def ^:private parameters-validation-middleware
  (letfn [(prepare [parameters]
            (reduce-kv
             (fn [acc key spec]
               (let [newkey (case key
                              :path :path-params
                              :query :query-params
                              :body :body-params
                              :multipart :multipart-params
                              (throw (ex-info "Not supported key on :parameters" {})))]
                 (assoc acc newkey {:key key
                                    :fn #(st/validate spec %)})))
             {} parameters))

          (validate [request parameters debug]
            (reduce-kv
             (fn [req key spec]
               (let [[errors, result] ((:fn spec) (get req key))]
                 (if errors
                   (ex/raise :type :validation
                             :code (:key spec)
                             :context errors
                             :prop key
                             :value (get req key)
                             :message "Invalid data")
                   (assoc-in req [:parameters (:key spec)] result))))
             request parameters))

          (compile-struct [route opts parameters]
            (let [parameters (prepare parameters)]
              (fn [handler]
                (fn
                  ([request]
                   (handler (validate request parameters)))
                  ([request respond raise]
                   (try
                     (handler (validate request parameters false) respond raise)
                     (catch Exception e
                       (raise e))))))))

          (prepare-spec [parameters]
            (reduce-kv (fn [acc key s]
                         (let [rk (case key
                                    :path :path-params
                                    :query :query-params
                                    :body :body-params
                                    :multipart :multipart-params
                                    (throw (ex-info "Not supported key on :parameters" {})))]
                           (assoc acc rk {:key key
                                          :fn #(us/conform s %)})))
                       {}
                       parameters))

          (validate-spec [request parameters]
            (reduce-kv
             (fn [req key spec]
               (let [[result errors] ((:fn spec) (get req key))]
                 (if errors
                   (ex/raise :type :validation
                             :code :parameters
                             :context {:problems (vec (::s/problems errors))
                                       :spec (::s/spec errors)
                                       :value (::s/value errors)})
                   (assoc-in req [:parameters (:key spec)] result))))
             request parameters))

          (compile-spec [route opts parameters]
            (let [parameters (prepare-spec parameters)]
              (fn [handler]
                (fn
                  ([request]
                   (handler (validate-spec request parameters)))
                  ([request respond raise]
                   (try
                     (handler (validate-spec request parameters) respond raise)
                     (catch Exception e
                       (raise e))))))))

          (compile [route opts]
            (when-let [parameters (:parameters route)]
              (if (= :spec (:validation route))
                (compile-spec route opts parameters)
                (compile-struct route opts parameters))))]
    {:name ::parameters-validation-middleware
     :compile compile}))

(def ^:private session-middleware
  (let [options {:store (cookie-store {:key "a 16-byte secret"})
                 :cookie-name "session"
                 :cookie-attrs {:same-site :lax
                                :http-only false}}]
    {:name ::session-middleware
     :wrap #(wrap-session % options)}))

(def cors-conf
  {:origin #{"http://localhost:3449"}
   :max-age 3600
   :allow-credentials true
   :allow-methods #{:post :put :get :delete}
   :allow-headers #{:x-requested-with :content-type :cookie}})

(def ^:private cors-middleware
  {:name ::cors-middleware
   :wrap (fn [handler]
           (let [cors (:http-server-cors cfg/config)]
             (if (string? cors)
               (->> (assoc cors-conf :origin #{cors})
                    (wrap-cors handler))
               handler)))})

(def ^:private etag-middleware
  {:name ::etag-middleware
   :wrap wrap-etag})

(def ^:private exception-middleware
  (exception/create-exception-middleware
   (assoc exception/default-handlers
          :muuntaja/decode errors/errors-handler
          ::exception/default errors/errors-handler)))

(def authorization-middleware
  {:name ::authorization-middleware
   :wrap (fn [handler]
           (fn
             ([request]
              (if-let [identity (get-in request [:session :user-id])]
                (handler (assoc request :identity identity :user identity))
                (rsp/forbidden nil)))
             ([request respond raise]
              (if-let [identity (get-in request [:session :user-id])]
                (handler (assoc request :identity identity :user identity) respond raise)
                (respond (rsp/forbidden nil))))))})

(def format-response-middleware
  (letfn [(process-response [{:keys [body] :as rsp}]
            (if (coll? body)
              (let [body (t/encode body {:type :json-verbose})]
                (-> rsp
                    (assoc :body body)
                    (update :headers assoc "content-type" "application/transit+json")))
              rsp))]
    {:name ::format-response-middleware
     :wrap (fn [handler]
             (fn
               ([request]
                (process-response (handler request)))
               ([request respond raise]
                (handler request (fn [res] (respond (process-response res))) raise))))}))

(def parse-request-middleware
  (letfn [(get-content-type [request]
            (or (:content-type request)
                (get (:headers request) "content-type")))

          (slurp-bytes [body]
            (with-open [input (io/input-stream body)
                        output (java.io.ByteArrayOutputStream. (.available input))]
              (io/copy input output)
              (.toByteArray output)))

          (parse-body [body]
            (let [^bytes body (slurp-bytes body)]
              (when (pos? (alength body))
                (t/decode body))))

          (process-request [request]
            (let [ctype (get-content-type request)]
              (if (= "application/transit+json" ctype)
                (try
                  (let [body (parse-body (:body request))]
                    (assoc request :body-params body))
                  (catch Exception e
                    (ex/raise :type :parse
                              :message "Unable to parse transit from request body."
                              :cause e)))
                request)))]

    {:name ::parse-request-middleware
     :wrap (fn [handler]
             (fn
               ([request]
                (handler (process-request request)))
               ([request respond raise]
                (let [^HttpInput body (:body request)]
                  (try
                    (handler (process-request request) respond raise)
                    (catch Exception e
                      (raise e)))))))}))

(def middleware
  [cors-middleware
   session-middleware

   ;; etag
   etag-middleware

   parameters/parameters-middleware

   ;; Format the body into transit
   format-response-middleware

   ;; main exception handling
   exception-middleware

   ;; parse transit format from request body
   parse-request-middleware

   ;; multipart parsing
   multipart-params-middleware

   ;; parameters normalization
   normalize-params-middleware

   ;; parameters validation
   parameters-validation-middleware])

(defn handler
  [invar]
  (let [metadata (meta invar)
        hlrdata (-> metadata
                    (dissoc :arglist :line :column :file :ns)
                    (assoc :handler (transform-handler (var-get invar))
                           :fullname (symbol (str (:ns metadata)) (str (:name metadata)))))]
    (cond-> hlrdata
      (:doc metadata) (assoc :description (:doc metadata)))))

(defn options-handler
  [request respond raise]
  (let [methods (->> request rr/get-match :result (keep (fn [[k v]] (if v k))))
        allow (->> methods (map (comp str/upper name)) (str/join ","))]
    (respond {:status 200, :body "", :headers {"Allow" allow}})))
