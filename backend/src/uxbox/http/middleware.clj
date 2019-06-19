;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.middleware
  (:require [promesa.core :as p]
            [cuerdas.core :as str]
            [struct.core :as st]
            [reitit.ring :as rr]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.exception :as exception]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [uxbox.http.etag :refer [wrap-etag]]
            [uxbox.http.cors :refer [wrap-cors]]
            [uxbox.http.errors :as errors]
            [uxbox.http.response :as rsp]
            [uxbox.util.data :refer [normalize-attrs]]
            [uxbox.util.exceptions :as ex]))

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
                      (prn handler)
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
                                    :fn #(st/validate % spec)})))
             {} parameters))

          (validate [request parameters debug]
            (reduce-kv
             (fn [req key spec]
               (let [[errors, result] ((:fn spec) (get req key))]
                 (if errors
                   (ex/raise :type :validation
                             :code (:key spec)
                             :context errors
                             :value (get req key)
                             :message "Invalid data")
                   (assoc-in req [:parameters (:key spec)] result))))
             request parameters))

          (compile [route opts]
            (when-let [parameters (:parameters route)]
              (let [parameters (prepare parameters)]
                (fn [handler]
                  (fn
                    ([request]
                     (handler (validate request parameters)))
                    ([request respond raise]
                     (try
                       (handler (validate request parameters false) respond raise)
                       (catch Exception e
                         (raise e)))))))))]
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
  {:origin #{"http://127.0.0.1:3449"}
   :max-age 3600
   :allow-credentials true
   :allow-methods #{:post :put :get :delete}
   :allow-headers #{:x-requested-with :content-type :cookie}})

(def ^:private cors-middleware
  {:name ::cors-middleware
   :wrap #(wrap-cors % cors-conf)})

(def ^:private etag-middleware
  {:name ::etag-middleware
   :wrap wrap-etag})

(def ^:private exception-middleware
  (exception/create-exception-middleware
   (assoc exception/default-handlers
          ::exception/default errors/errors-handler
          ::exception/wrap errors/wrap-print-errors)))

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

(def middleware
  [cors-middleware
   session-middleware

   ;; etag
   etag-middleware

   parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   ;; encoding response body
   muuntaja/format-response-middleware
   ;; exception handling
   exception-middleware
   ;; decoding request body
   muuntaja/format-request-middleware

   ;; multipart
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
