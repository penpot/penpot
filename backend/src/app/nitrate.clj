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
  [cfg method uri shared-key profile-id request-params]
  (fn []
    (http/req! cfg (cond-> {:method method
                            :headers {"content-type" "application/json"
                                      "accept" "application/json"
                                      "x-shared-key" shared-key
                                      "x-profile-id" (str profile-id)}
                            :uri uri
                            :version :http1.1}
                     (= method :post) (assoc :body (json/encode request-params))))))

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
  [cfg method uri schema {:keys [::rpc/profile-id request-params] :as params}]
  (let [shared-key     (-> cfg ::setup/shared-keys :nitrate)
        full-http-call (-> (request-builder cfg method uri shared-key profile-id request-params)
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
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]])

(def ^:private schema:team
  [:map
   [:id ::sm/uuid]
   [:organizationId ::sm/uuid]
   [:yourPenpot :boolean]])

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
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/teams/"
                             team-id)
                        schema:organization params)))

(defn- set-team-org
  [cfg {:keys [organization-id team-id is-default] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)
        params (assoc params :request-params {:teamId team-id
                                              :yourPenpot (true? is-default)})]
    (request-to-nitrate cfg :post
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/add-team")
                        schema:team params)))

(defn- get-subscription
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/subscriptions/"
                             profile-id)
                        schema:subscription params)))

(defn- get-connectivity
  [cfg params]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/connectivity")
                        schema:connectivity params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::client
  [_ cfg]
  (when (contains? cf/flags :nitrate)
    {:get-team-org     (partial get-team-org cfg)
     :set-team-org     (partial set-team-org cfg)
     :get-subscription (partial get-subscription cfg)
     :connectivity     (partial get-connectivity cfg)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn add-nitrate-licence-to-profile
  "Enriches a profile map with subscription information from Nitrate.
  Adds a :subscription field containing the user's license details.
  Returns the original profile unchanged if the request fails."
  [cfg profile]
  (try
    (let [subscription (call cfg :get-subscription {:profile-id (:id profile)})]
      (assoc profile :subscription subscription))
    (catch Throwable cause
      (l/error :hint "failed to get nitrate licence"
               :profile-id (:id profile)
               :cause cause)
      profile)))

(defn add-org-info-to-team
  "Enriches a team map with organization information from Nitrate.
  Adds organization-id, organization-name, organization-slug, and your-penpot fields.
  Returns the original team unchanged if the request fails or org data is nil."
  [cfg team params]
  (try
    (let [params (assoc (or params {}) :team-id (:id team))
          org (call cfg :get-team-org params)]
      (if (some? org)
        (assoc team
               :organization-id (:id org)
               :organization-name (:name org)
               :organization-slug (:slug org)
               :is-default (or (:is-default team) (true? (:isYourPenpot org))))
        team))
    (catch Throwable cause
      (l/error :hint "failed to get team organization info"
               :team-id (:id team)
               :cause cause)
      team)))

(defn set-team-organization
  "Associates a team with an organization in Nitrate.
  Requires organization-id and is-default in params.
  Throws an exception if the request fails."
  [cfg team params]
  (let [params (assoc (or params {})
                      :team-id (:id team)
                      :organization-id (:organization-id params)
                      :is-default (:is-default params))
        result (call cfg :set-team-org params)]
    (when (nil? result)
      (throw (ex-info "Failed to set team organization"
                      {:team-id (:id team)
                       :organization-id (:organization-id params)})))
    team))

(defn connectivity
  [cfg]
  (call cfg :connectivity {}))
