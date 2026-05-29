;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.nitrate
  "Module that make calls to the external nitrate aplication"
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.time :as ct]
   [app.common.types.organization :as cto]
   [app.config :as cf]
   [app.http.client :as http]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [clojure.core :as c]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-builder
  [cfg method uri shared-key profile-id request-params]
  (fn []
    (http/req cfg
              (cond-> {:method method
                       :headers {"content-type" "application/json"
                                 "accept" "application/json"
                                 "x-shared-key" shared-key
                                 "x-profile-id" (str profile-id)}
                       :uri uri
                       :version :http1.1}
                (= method :post) (assoc :body (json/encode request-params :key-fn json/write-camel-key)))
              {:skip-ssrf-check? true})))

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


(defn- with-validate [handler uri schema & {:keys [throw-on-error?]}]
  (fn []
    (let [response (handler)
          status (:status response)]
      (cond
        (nil? status)
        (do
          (l/error :hint "couldn't do the nitrate request, it is probably down"
                   :uri uri)
          (ex/raise :type :nitrate-unavailable
                    :hint (str "nitrate is unreachable at " uri)))

        (>= status 500)
        ;; Nitrate is up enough to answer (or the proxy is) but the
        ;; service itself is failing; treat as unavailable so callers
        ;; surface the static error page.
        (do
          (l/error :hint "nitrate request failed with server error status"
                   :uri uri
                   :status status
                   :body (:body response))
          (ex/raise :type :nitrate-unavailable
                    :status status
                    :hint (str "nitrate is unavailable, HTTP " status " at " uri)))

        (>= status 400)
        ;; For client error status codes (4xx), fail immediately without validation
        (do
          (when (not= status 404) ;; Don't need to log 404
            (l/error :hint "nitrate request failed with error status"
                     :uri uri
                     :status status
                     :body (:body response)))
          (if throw-on-error?
            (ex/raise :type :nitrate-http-error
                      :status status
                      :hint (str "nitrate HTTP " status " at " uri))
            nil))
        (= status 204) ;; 204 doesn't return any body
        nil
        :else ;; For success status codes, validate the response
        (let [coercer-http (sm/coercer schema
                                       :type :validation
                                       :hint (str "invalid data received calling " uri))
              data (-> response :body (json/decode :key-fn json/read-kebab-key))]
          (try
            (coercer-http data)
            (catch Exception e
              ;; TODO Error handling
              (l/error :hint "error validating json response" :cause e)
              nil)))))))

(defn- request-to-nitrate
  [cfg method uri schema {:keys [::rpc/profile-id request-params throw-on-error?] :as params}]
  (let [shared-key     (-> cfg ::setup/shared-keys :nitrate)
        full-http-call (-> (request-builder cfg method uri shared-key profile-id request-params)
                           (with-retries 3)
                           (with-validate uri schema :throw-on-error? throw-on-error?))]
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

(def ^:private schema:org-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:owner-id ::sm/uuid]
   [:teams
    [:vector
     [:map
      [:id ::sm/uuid]
      [:is-your-penpot :boolean]]]]])

(def ^:private schema:profile-org
  [:map
   [:is-member :boolean]
   [:organization-id {:optional true} [:maybe ::sm/uuid]]
   [:default-team-id {:optional true} [:maybe ::sm/uuid]]])


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
   [:manual :boolean]
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

(defn- get-team-org-api
  [cfg {:keys [team-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/teams/"
                             team-id)
                        cto/schema:team-with-organization params)))

(defn- get-org-membership-api
  [cfg {:keys [profile-id organization-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/members/"
                             profile-id)
                        schema:profile-org params)))

(defn- get-org-membership-by-team-api
  [cfg {:keys [profile-id team-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/teams/"
                             team-id
                             "/users/"
                             profile-id)
                        schema:profile-org params)))


(defn- get-org-summary-api
  [cfg {:keys [organization-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/summary")
                        schema:org-summary params)))

(defn- get-owned-orgs-api
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/users/"
                             profile-id
                             "/owned-organizations")
                        [:vector schema:org-summary]
                        params)))

(def ^:private schema:org-summary-counts
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]
   [:team-count ::sm/int]
   [:member-count ::sm/int]
   [:avatar-bg-url {:optional true} [:maybe ::sm/uri]]
   [:logo-id {:optional true} [:maybe ::sm/uuid]]])

(defn- get-owned-orgs-summary-api
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)
        orgs    (request-to-nitrate cfg :get
                                    (str baseuri
                                         "/api/users/"
                                         profile-id
                                         "/owned-organizations-summary")
                                    [:vector schema:org-summary-counts]
                                    params)]
    (mapv (fn [org]
            (if-let [logo-id (:logo-id org)]
              (assoc org :custom-photo (str (cf/get :public-uri) "/assets/by-id/" logo-id))
              org))
          orgs)))

(defn- delete-owned-orgs-api
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :post
                        (str baseuri
                             "/api/users/"
                             profile-id
                             "/delete-owned-organizations")
                        nil params)))

(defn- set-team-org-api
  [cfg {:keys [organization-id team-id is-default] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)
        params (assoc params :request-params {:team-id team-id
                                              :is-your-penpot (true? is-default)})
        team (request-to-nitrate cfg :post
                                 (str baseuri
                                      "/api/organizations/"
                                      organization-id
                                      "/add-team")
                                 cto/schema:team-with-organization params)
        custom-photo (when-let [logo-id (dm/get-in team [:organization :logo-id])]
                       (str (cf/get :public-uri) "/assets/by-id/" logo-id))]
    (cond-> team
      custom-photo
      (assoc-in [:organization :custom-photo] custom-photo))))

(defn- add-profile-to-org-api
  [cfg {:keys [profile-id organization-id team-id email] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)
        request-params (cond-> {:user-id profile-id :team-id team-id}
                         (some? email) (assoc :email email))
        params (assoc params :request-params request-params)]
    (request-to-nitrate cfg :post
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/add-user")
                        schema:profile-org params)))

(defn- remove-profile-from-org-api
  [cfg {:keys [profile-id organization-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)
        params (assoc params :request-params {:user-id profile-id})]
    (request-to-nitrate cfg :post
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/remove-user")
                        nil params)))

(defn- remove-profile-from-all-orgs-api
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :post
                        (str baseuri
                             "/api/users/"
                             profile-id
                             "/remove-organizations")
                        nil params)))

(defn- remove-team-from-org-api
  [cfg {:keys [team-id organization-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)
        params (assoc params :request-params {:team-id team-id})]
    (request-to-nitrate cfg :post
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/remove-team")
                        nil params)))

(defn- delete-team-api
  [cfg {:keys [team-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :delete
                        (str baseuri
                             "/api/teams/"
                             team-id)
                        nil params)))

(defn- get-subscription-api
  [cfg {:keys [profile-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/subscriptions/"
                             profile-id)
                        schema:subscription params)))

(defn- get-connectivity-api
  [cfg params]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/connectivity")
                        schema:connectivity params)))

(def ^:private schema:redeem-result
  [:map
   [:cancel-at [:maybe schema:timestamp]]])

(defn- get-org-permissions-api
  [cfg {:keys [organization-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/permissions")
                        [:map
                         [:organization-id ::sm/uuid]
                         [:owner-id ::sm/uuid]
                         [:permissions [:map-of :keyword :string]]]
                        params)))

(defn- get-org-members-api
  [cfg {:keys [organization-id] :as params}]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :get
                        (str baseuri
                             "/api/organizations/"
                             organization-id
                             "/members-list")
                        [:vector ::sm/uuid]
                        params)))

(defn- redeem-activation-code-api
  [cfg params]
  (let [baseuri (cf/get :nitrate-backend-uri)]
    (request-to-nitrate cfg :post
                        (str baseuri "/api/activation-codes/redeem")
                        schema:redeem-result
                        (assoc params :throw-on-error? true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::client
  [_ cfg]
  (when (contains? cf/flags :nitrate)
    {:get-team-org                 (partial get-team-org-api cfg)
     :set-team-org                 (partial set-team-org-api cfg)
     :get-org-membership           (partial get-org-membership-api cfg)
     :get-org-membership-by-team   (partial get-org-membership-by-team-api cfg)
     :get-org-summary              (partial get-org-summary-api cfg)
     :get-owned-orgs               (partial get-owned-orgs-api cfg)
     :get-owned-orgs-summary       (partial get-owned-orgs-summary-api cfg)
     :get-org-members              (partial get-org-members-api cfg)
     :delete-owned-orgs            (partial delete-owned-orgs-api cfg)
     :add-profile-to-org           (partial add-profile-to-org-api cfg)
     :remove-profile-from-org      (partial remove-profile-from-org-api cfg)
     :remove-profile-from-all-orgs (partial remove-profile-from-all-orgs-api cfg)
     :get-org-permissions          (partial get-org-permissions-api cfg)
     :delete-team                  (partial delete-team-api cfg)
     :remove-team-from-org         (partial remove-team-from-org-api cfg)
     :get-subscription             (partial get-subscription-api cfg)
     :connectivity                 (partial get-connectivity-api cfg)
     :redeem-activation-code       (partial redeem-activation-code-api cfg)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn add-nitrate-licence-to-profile
  "Enriches a profile map with subscription information from Nitrate.
  Adds a :subscription field containing the user's license details.
  Returns the original profile unchanged if the request fails for a reason
  other than Nitrate being unreachable. When Nitrate is unreachable the
  `:nitrate-unavailable` exception propagates so the request is rejected."
  [cfg profile]
  (try
    (let [subscription (call cfg :get-subscription {:profile-id (:id profile)})]
      (assoc profile :subscription subscription))
    (catch Throwable cause
      (if (= :nitrate-unavailable (-> cause ex-data :type))
        (throw cause)
        (do
          (l/error :hint "failed to get nitrate licence"
                   :profile-id (:id profile)
                   :cause cause)
          profile)))))

(defn add-org-info-to-team
  "Enriches a team map with organization information from Nitrate.
  Adds organization-id, organization-name, organization-slug, organization-owner-id, and your-penpot fields.
  Returns the original team unchanged if the request fails or org data is nil.
  Propagates `:nitrate-unavailable` so the request is rejected when Nitrate is unreachable."
  [cfg team params]
  (try
    (let [params        (assoc (or params {}) :team-id (:id team))
          team-with-org (call cfg :get-team-org params)
          org           (:organization team-with-org)]
      (if (some? org)
        (-> (cto/apply-organization team (assoc org :custom-photo
                                                (when-let [logo-id (:logo-id org)]
                                                  (str (cf/get :public-uri) "/assets/by-id/" logo-id))))
            (assoc :is-default (or (:is-default team) (true? (:is-your-penpot team-with-org)))))
        team))
    (catch Throwable cause
      (if (= :nitrate-unavailable (-> cause ex-data :type))
        (throw cause)
        (do
          (l/error :hint "failed to get team organization info"
                   :team-id (:id team)
                   :cause cause)
          team)))))

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
      (ex/raise :type :internal
                :code :failed-to-set-team-org
                :context {:team-id (:id team)
                          :organization-id (:organization-id params)}))
    team))



