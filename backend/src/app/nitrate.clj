;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.nitrate
  "Module that make calls to the external nitrate aplication"
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.http.client :as http]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [app.util.json :as json]
   [clojure.core :as c]
   [integrant.core :as ig]))


;; TODO Extract to env
(def baseuri "http://localhost:3000")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coercer
  [schema & {:as opts}]
  (let [decode-fn (sm/decoder schema sm/json-transformer)
        check-fn  (sm/check-fn schema opts)]
    (fn [data]
      (-> data decode-fn check-fn))))


(defn- request-builder
  [cfg method uri management-key profile-id]
  (fn []
    (http/req! cfg {:method method
                    :headers {"content-type" "application/json"
                              "accept" "application/json"
                              "x-shared-key" management-key
                              "x-profile-id" (str profile-id)}
                    :uri uri
                    :version :http1.1})))


(defn- with-retries
  [handler max-retries]
  (fn []
    (loop [attempt 1]
      (let [result (try
                     (handler)
                     (catch Exception e
                       (if (< attempt max-retries)
                         ::retry
                         (do
                           ;; TODO Error handling
                           (l/error :hint "request fail after multiple retries" :cause e)
                           nil))))]
        (if (= result ::retry)
          (recur (inc attempt))
          result)))))


(defn- with-validate [handler uri schema]
  (fn []
    (let [coercer-http   (coercer schema
                                  :type :validation
                                  :hint (str "invalid data received calling " uri))]
      (try
        (coercer-http (-> (handler) :body json/decode))
        (catch Exception e
          ;; TODO Error handling
          (l/error :hint "error validating json response" :cause e)
          nil)))))

(defn- request-to-nitrate
  [{:keys [::management-key] :as cfg} method uri schema {:keys [::rpc/profile-id] :as params}]
  (let [full-http-call (-> (request-builder cfg method uri management-key profile-id)
                           (with-retries 3)
                           (with-validate uri schema))]
    (full-http-call)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn call
  [cfg method params]
  (when (contains? cf/flags :nitrate)
    (let [client (get cfg ::client)
          method (get client method)]
      (method params))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:organization
  [:map
   [:id ::sm/int]
   [:name ::sm/text]])


(defn- get-team-org
  [cfg {:keys [team-id] :as params}]
  (request-to-nitrate cfg :get (str baseuri "/api/teams/" (str team-id)) schema:organization params))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::client
  [_ {:keys [::setup/props] :as cfg}]
  (if (contains? cf/flags :nitrate)
    (let [management-key (or (cf/get :management-api-key)
                             (get props :management-key))
          cfg (assoc cfg ::management-key management-key)]
      {:get-team-org (partial get-team-org cfg)})
    {}))

(defmethod ig/halt-key! ::client
  [_ {:keys []}]
  (do :stuff))
