;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.nitrate
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.http.client :as http]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [app.util.json :as json]
   [clojure.core :as c]
   [integrant.core :as ig]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coercer
  [schema & {:as opts}]
  (let [decode-fn (sm/decoder schema sm/json-transformer)
        check-fn  (sm/check-fn schema opts)]
    (fn [data]
      (-> data decode-fn check-fn))))

(defn http-validated-call
  [{:keys [::setup/props] :as cfg} method uri schema {:keys [::rpc/profile-id] :as params}]
  (let [coercer-http   (coercer schema
                                :type :validation
                                :hint (str "invalid data received calling " uri))
        management-key (or (cf/get :management-api-key)
                           (get props :management-key))
        ;; TODO cache
        ;; TODO retries
        ;; TODO error handling
        rsp            (try
                         (http/req! cfg {:method method
                                         :headers {"User-Agent" "curl/7.85.0"
                                                   "content-type" "application/json"
                                                   "accept" "application/json"
                                                   "x-shared-key" management-key
                                                   "x-profile-id" (str profile-id)}
                                         :uri uri
                                         :version :http1.1})
                         (catch Exception e
                           (println "Error:" (.getMessage e))))]
    (try
      (coercer-http (-> rsp :body json/decode))
      (catch Exception e
        (println "Error:" (.getMessage e))
        nil))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn call
  [cfg method params]
  (when (contains? cf/flags :nitrate)
    (let [instance (get cfg ::instance)
          method (get instance method)]
      (method params))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO Extract to env
(def baseuri "http://localhost:3000")


(def ^:private schema:organization
  [:map
   [:id ::sm/int]
   [:name ::sm/text]])

(defn- get-team-org
  [cfg {:keys [team-id] :as params}]
  (http-validated-call cfg :get (str baseuri "/api/teams/" (str team-id)) schema:organization params))

(defmethod ig/init-key ::instance
  [_ cfg]
  (if (contains? cf/flags :nitrate)
    {:get-team-org (partial get-team-org cfg)}
    {}))

(defmethod ig/halt-key! ::instance
  [_ {:keys []}]
  (do :stuff))
