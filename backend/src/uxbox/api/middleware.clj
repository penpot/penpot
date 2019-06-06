;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.middleware
  (:require [reitit.core :as rc]
            [struct.core :as st]
            [promesa.core :as p]
            [uxbox.util.data :refer [normalize-attrs]]
            [uxbox.util.exceptions :as ex]))

;; (extend-protocol rc/Expand
;;   clojure.lang.Var
;;   (expand [this opts]
;;     (merge (rc/expand (deref this) opts)
;;            {::handler-metadata (meta this)})))

(defn transform-handler
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

(defn handler
  [invar]
  (let [metadata (meta invar)
        hlrdata (-> metadata
                    (dissoc :arglist :line :column :file :ns)
                    (assoc :handler (transform-handler (var-get invar))
                           :fullname (symbol (str (:ns metadata)) (str (:name metadata)))))]
    (cond-> hlrdata
      (:doc metadata) (assoc :description (:doc metadata)))))

(def normalize-params-middleware
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


;; --- Validation

(def parameters-validation-middleware
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
                             :message "Invalid data")

                   (assoc-in req [:parameters (:key spec)] result))))
             request parameters))]

    {:name ::parameters-validation-middleware
     :compile (fn [route opts]
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
                             (raise e)))))))))}))
