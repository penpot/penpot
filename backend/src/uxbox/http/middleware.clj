;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.middleware
  (:require
   [promesa.core :as p]
   [vertx.web :as vw]
   [uxbox.config :as cfg]
   [uxbox.common.exceptions :as ex]
   [uxbox.util.transit :as t])
  (:import
   io.vertx.ext.web.RoutingContext
   io.vertx.ext.web.FileUpload
   io.vertx.core.buffer.Buffer))

(defn- wrap-parse-request-body
  [handler]
  (fn [{:keys [headers body method] :as request}]
    (let [mtype (get headers "content-type")]
      (if (and (= "application/transit+json" mtype)
               (not= method :get))
        (try
          (let [params (t/decode (t/buffer->bytes body))]
            (handler (assoc request :body-params params)))
          (catch Exception e
            (ex/raise :type :parse
                      :message "Unable to parse transit from request body."
                      :cause e)))
        (handler request)))))

(def parse-request-body
  {:name ::parse-request-body
   :compile (constantly wrap-parse-request-body)})

(defn- impl-format-response-body
  [response]
  (let [body (:body response)
        type (if (:debug-humanize-transit cfg/config)
               :json-verbose
               :json)]
    (cond
      (coll? body)
      (-> response
          (assoc :body (t/bytes->buffer (t/encode body {:type type})))
          (update :headers assoc
                  "content-type"
                  "application/transit+json"))

      (nil? body)
      (assoc response :status 204 :body "")

      :else
      response)))

(defn- wrap-format-response-body
  [handler]
  (fn [request]
    (-> (p/do! (handler request))
        (p/then' (fn [response]
                   (cond-> response
                     (map? response) (impl-format-response-body)))))))

(def format-response-body
  {:name ::format-response-body
   :compile (constantly wrap-format-response-body)})


(defn- wrap-method-match
  [handler]
  (fn [request]))

(def method-match
  {:name ::method-match
   :compile (fn [data opts]
              (when-let [method (:method data)]
                (fn [handler]
                  (fn [request]
                    (if (= (:method request) method)
                      (handler request)
                      {:status 405 :body ""})))))})
