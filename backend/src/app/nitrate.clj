(ns app.nitrate
  "Module that make calls to the external nitrate aplication"
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.time :as ct]
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

(defn- request-builder
  [cfg method uri shared-key profile-id]
  (fn []
    (http/req! cfg {:method method
                    :headers {"content-type" "application/json"
                              "accept" "application/json"
                              "x-shared-key" shared-key
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
    (let [coercer-http (sm/coercer schema
                                   :type :validation
                                   :hint (str "invalid data received calling " uri))]
      (try
        (coercer-http (-> (handler) :body json/decode))
        (catch Exception e
          ;; TODO Error handling
          (l/error :hint "error validating json response" :cause e)
          nil)))))

(defn- request-to-nitrate
  [cfg method uri schema {:keys [::rpc/profile-id] :as params}]
  (let [shared-key     (-> cfg ::setup/shared-keys :nitrate)
        full-http-call (-> (request-builder cfg method uri shared-key profile-id)
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
   [:id ::sm/text]
   [:name ::sm/text]])

;; TODO Unify with schemas on backend/src/app/http/management.clj
(def ^:private schema:timestamp
  (sm/type-schema
   {:type ::timestamp
    :pred ct/inst?
    :type-properties
    {:title "inst"
     :description "The same as :app.common.time/inst but encodes to epoch"
     :error/message "should be an instant"
     :gen/gen (->> (sg/small-int)
                   (sg/fmap (fn [v] (ct/inst v))))
     :decode/string ct/inst
     :encode/string inst-ms
     :decode/json ct/inst
     :encode/json inst-ms}}))

(def ^:private schema:subscription
  [:map {:title "Subscription"}
   [:id ::sm/text]
   [:customer-id ::sm/text]
   [:type [:enum
           "unlimited"
           "professional"
           "enterprise"
           "nitrate"]]
   [:status [:enum
             "active"
             "canceled"
             "incomplete"
             "incomplete_expired"
             "past_due"
             "paused"
             "trialing"
             "unpaid"]]

   [:billing-period [:enum
                     "month"
                     "day"
                     "week"
                     "year"]]
   [:quantity :int]
   [:description [:maybe ::sm/text]]
   [:created-at schema:timestamp]
   [:start-date [:maybe schema:timestamp]]
   [:ended-at [:maybe schema:timestamp]]
   [:trial-end [:maybe schema:timestamp]]
   [:trial-start [:maybe schema:timestamp]]
   [:cancel-at [:maybe schema:timestamp]]
   [:canceled-at [:maybe schema:timestamp]]
   [:current-period-end [:maybe schema:timestamp]]
   [:current-period-start [:maybe schema:timestamp]]
   [:cancel-at-period-end :boolean]

   [:cancellation-details
    [:map {:title "CancellationDetails"}
     [:comment [:maybe ::sm/text]]
     [:reason [:maybe ::sm/text]]
     [:feedback [:maybe
                 [:enum
                  "customer_service"
                  "low_quality"
                  "missing_feature"
                  "other"
                  "switched_service"
                  "too_complex"
                  "too_expensive"
                  "unused"]]]]]])

(def ^:private schema:connectivity
  [:map
   [:licenses ::sm/boolean]])

(defn- get-team-org
  [cfg {:keys [team-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get (str baseuri "/api/teams/" (str team-id)) schema:organization params)))

(defn- get-subscription
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get (str baseuri "/api/subscriptions/" (str profile-id)) schema:subscription params)))

(defn- get-connectivity
  [cfg params]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get (str baseuri "/api/connectivity") schema:connectivity params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::client
  [_ cfg]
  (when (contains? cf/flags :nitrate)
    {:get-team-org     (partial get-team-org cfg)
     :get-subscription (partial get-subscription cfg)
     :connectivity     (partial get-connectivity cfg)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn add-nitrate-licence-to-profile
  [cfg profile]
  (try
    (let [subscription (call cfg :get-subscription {:profile-id (:id profile)})]
      (assoc profile :subscription subscription))
    (catch Throwable cause
      (l/error :hint "failed to get nitrate licence"
               :profile-id (:id profile)
               :cause cause)
      profile)))

(defn add-org-to-team
  [cfg team params]
  (let [params (assoc (or params {}) :team-id (:id team))
        org (call cfg :get-team-org params)]
    (assoc team :organization-id (:id org) :organization-name (:name org))))

(defn connectivity
  [cfg]
  (call cfg :connectivity {}))
