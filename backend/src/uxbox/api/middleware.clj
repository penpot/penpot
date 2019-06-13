;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.middleware
  (:require [muuntaja.core :as m]
            [promesa.core :as p]
            [reitit.core :as rc]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
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
           (letfn [(transform-request [request]
                     (if-let [data (get request :query-params)]
                       (assoc request :query-params (normalize-attrs data))
                       request))]
             (fn
               ([request] (handler (transform-request request)))
               ([request respond raise]
                (try
                  (try
                    (let [request (transform-request request)]
                      (handler (transform-request request) respond raise))
                    (catch Exception e
                      (raise e))))))))})


(def ^:private parameters-validation-middleware
  (letfn [(prepare [parameters]
            (reduce-kv
             (fn [acc key spec]
               (let [newkey (case key
                              :path :path-params
                              :query :query-params
                              :body :body-params
                              (throw (ex-info "Not supported key on :parameters" {})))]
                 (assoc acc newkey {:key key
                                    :fn #(st/validate % spec)})))
             {} parameters))

          (validate [request parameters debug]
            (reduce-kv
             (fn [req key spec]
               (let [[errors, result] ((:fn spec) (get req key))]
                 (if errors
                   (ex/raise :type :parameters-validation
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

(def ^:private muuntaja-instance
  (m/create (update-in m/default-options [:formats "application/transit+json"]
                       merge {:encoder-opts {:handlers t/+write-handlers+}
                              :decoder-opts {:handlers t/+read-handlers+}})))
(def router-options
  {;;:reitit.middleware/transform dev/print-request-diffs
   :data {:muuntaja muuntaja-instance
          :middleware [session-middleware
                       parameters/parameters-middleware
                       normalize-params-middleware
                       ;; content-negotiation
                       muuntaja/format-negotiate-middleware
                       ;; encoding response body
                       muuntaja/format-response-middleware
                       ;; exception handling
                       api-errors/exception-middleware
                       ;; decoding request body
                       muuntaja/format-request-middleware
                       ;; validation
                       parameters-validation-middleware
                       ;; multipart
                       multipart/multipart-middleware]}})

(defn handler
  [invar]
  (let [metadata (meta invar)
        hlrdata (-> metadata
                    (dissoc :arglist :line :column :file :ns)
                    (assoc :handler (transform-handler (var-get invar))
                           :fullname (symbol (str (:ns metadata)) (str (:name metadata)))))]
    (cond-> hlrdata
      (:doc metadata) (assoc :description (:doc metadata)))))

