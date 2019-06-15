;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.middleware
  (:require [muuntaja.core :as m]
            [promesa.core :as p]
            [reitit.core :as rc]
            [reitit.dev.pretty :as pretty]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.exception :as exception]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [struct.core :as st]
            [uxbox.api.errors :as api-errors]
            [uxbox.util.data :refer [normalize-attrs]]
            [uxbox.util.exceptions :as ex]
            [uxbox.util.transit :as t]))

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
                 :cookie-attrs {:same-site :lax :http-only true}}]
    {:name ::session-middleware
     :wrap #(wrap-session % options)}))

;; (def ^:private cors-middleware
;;   {:name ::cors-middleware
;;    :wrap #(wrap-cors %
;;                      :access-control-allow-origin [#".*"]
;;                      :access-control-allow-methods [:get :put :post :delete]
;;                      :access-control-allow-headers ["x-requested-with"
;;                                                     "content-type"
;;                                                     "authorization"])})

(def ^:private exception-middleware
  (exception/create-exception-middleware
   (assoc exception/default-handlers
          ::exception/default api-errors/errors-handler
          ::exception/wrap api-errors/wrap-print-errors)))


(def ^:private muuntaja-instance
  (m/create (update-in m/default-options [:formats "application/transit+json"]
                       merge {:encoder-opts {:handlers t/+write-handlers+}
                              :decoder-opts {:handlers t/+read-handlers+}})))
(def router-options
  {;;:reitit.middleware/transform dev/print-request-diffs
   :exception pretty/exception
   :data {:muuntaja muuntaja-instance
          :middleware [session-middleware
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
                       parameters-validation-middleware]}})

(defn handler
  [invar]
  (let [metadata (meta invar)
        hlrdata (-> metadata
                    (dissoc :arglist :line :column :file :ns)
                    (assoc :handler (transform-handler (var-get invar))
                           :fullname (symbol (str (:ns metadata)) (str (:name metadata)))))]
    (cond-> hlrdata
      (:doc metadata) (assoc :description (:doc metadata)))))

