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
   [app.common.types.organization :as cto
    :refer [schema:nitrate-sso]]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.http.client :as http]
   [app.http.session :as session]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [app.util.cache :as cache]
   [clojure.core :as c]
   [clojure.string :as str]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- join-path-segments
  "Build a single relative path from Nitrate URI segments, normalizing slashes."
  [segments]
  (let [path (->> segments (map str) (str/join "/"))]
    (->> (str/split path #"/")
         (remove str/blank?)
         (str/join "/"))))

(defn- join-base-uri
  "Join path segments to a base URI."
  [base-uri & segments]
  (u/join (u/ensure-path-slash base-uri)
          (join-path-segments segments)))

(defn- generate-nitrate-uri
  "Joins relative path segments to the Nitrate backend URI.
   Segments must not start with `/`"
  [& segments]
  (apply join-base-uri (cf/get :admin-console-uri) segments))

(defn- generate-public-uri
  "Joins relative path segments to the public backend URI.
   Segments must not start with `/`"
  [& segments]
  (apply join-base-uri (cf/get :public-uri) segments))

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
  (let [shared-key     (-> cfg ::setup/shared-keys :admin-console)
        full-http-call (-> (request-builder cfg method uri shared-key profile-id request-params)
                           (with-retries 3)
                           (with-validate uri schema :throw-on-error? throw-on-error?))]
    (full-http-call)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn call
  [cfg method params]
  (when (contains? cf/flags :admin-console)
    (let [client (get cfg ::client)
          method (get client method)]
      (method params))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:organization-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:owner-id ::sm/uuid]
   [:teams
    [:vector
     [:map
      [:id ::sm/uuid]
      [:is-your-penpot :boolean]]]]])

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

(def ^:private schema:profile-organization
  [:map
   [:is-member :boolean]
   [:organization-id {:optional true} [:maybe ::sm/uuid]]
   [:default-team-id {:optional true} [:maybe ::sm/uuid]]
   [:created-at {:optional true} [:maybe schema:timestamp]]])

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

(defn- get-team-organization-api
  [cfg {:keys [team-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri "api/teams/" team-id)
                      cto/schema:team-with-organization params))

(defn- get-organization-membership-api
  [cfg {:keys [profile-id organization-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/organizations/"
                       organization-id
                       "members/"
                       profile-id)
                      schema:profile-organization params))

(defn- get-organization-membership-by-team-api
  [cfg {:keys [profile-id team-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/teams/"
                       team-id
                       "users/"
                       profile-id)
                      schema:profile-organization params))

(defn- get-organization-summary-api
  [cfg {:keys [organization-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/organizations/"
                       organization-id
                       "summary")
                      schema:organization-summary params))

(defn- get-owned-organizations-api
  [cfg {:keys [profile-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/users/"
                       profile-id
                       "owned-organizations")
                      [:vector schema:organization-summary]
                      params))

(def ^:private schema:organization-summary-counts
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]
   [:team-count ::sm/int]
   [:member-count ::sm/int]
   [:avatar-bg-url {:optional true} [:maybe ::sm/uri]]
   [:logo-id {:optional true} [:maybe ::sm/uuid]]])

(defn- get-owned-organizations-summary-api
  [cfg {:keys [profile-id] :as params}]
  (let [organizations (request-to-nitrate cfg :get
                                          (generate-nitrate-uri
                                           "api/users/"
                                           profile-id
                                           "owned-organizations-summary")
                                          [:vector schema:organization-summary-counts]
                                          params)]
    (mapv (fn [organization]
            (if-let [logo-id (:logo-id organization)]
              (assoc organization :custom-photo (generate-public-uri "assets/by-id/" logo-id))
              organization))
          organizations)))

(defn- cleanup-deleted-penpot-user-api
  [cfg {:keys [profile-id] :as params}]
  (request-to-nitrate cfg :post
                      (generate-nitrate-uri
                       "api/users/"
                       profile-id
                       "cleanup-after-deletion")
                      nil params))

(defn- set-team-organization-api
  [cfg {:keys [organization-id team-id is-default] :as params}]
  (let [params (assoc params :request-params {:team-id team-id
                                              :is-your-penpot (true? is-default)})
        team (request-to-nitrate cfg :post
                                 (generate-nitrate-uri
                                  "api/organizations/"
                                  organization-id
                                  "add-team")
                                 cto/schema:team-with-organization params)
        custom-photo (when-let [logo-id (dm/get-in team [:organization :logo-id])]
                       (generate-public-uri "assets/by-id/" logo-id))]
    (cond-> team
      custom-photo
      (assoc-in [:organization :custom-photo] custom-photo))))

(defn- add-profile-to-organization-api
  [cfg {:keys [profile-id organization-id team-id email] :as params}]
  (let [request-params (cond-> {:user-id profile-id :team-id team-id}
                         (some? email) (assoc :email email))
        params (assoc params :request-params request-params)]
    (request-to-nitrate cfg :post
                        (generate-nitrate-uri
                         "api/organizations/"
                         organization-id
                         "add-user")
                        schema:profile-organization params)))

(defn- remove-profile-from-organization-api
  [cfg {:keys [profile-id organization-id user-who-delete-member deleted-by-role] :as params}]
  (let [request-params (cond-> {:user-id profile-id}
                         (some? user-who-delete-member)
                         (assoc :user-who-delete-member user-who-delete-member)
                         (some? deleted-by-role)
                         (assoc :deleted-by-role deleted-by-role))
        params (assoc params :request-params request-params)]
    (request-to-nitrate cfg :post
                        (generate-nitrate-uri
                         "api/organizations/"
                         organization-id
                         "remove-user")
                        nil params)))

(defn- remove-team-from-organization-api
  [cfg {:keys [team-id organization-id] :as params}]
  (let [params (assoc params :request-params {:team-id team-id})]
    (request-to-nitrate cfg :post
                        (generate-nitrate-uri
                         "api/organizations/"
                         organization-id
                         "remove-team")
                        nil params)))

(defn- delete-team-api
  [cfg {:keys [team-id] :as params}]
  (request-to-nitrate cfg :delete
                      (generate-nitrate-uri "api/teams/" team-id)
                      nil params))

(defn- get-subscription-api
  [cfg {:keys [profile-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri "api/subscriptions/" profile-id)
                      schema:subscription params))

(def ^:private schema:subscription-warning
  [:maybe
   [:map {:title "SubscriptionWarning"}
    [:type {:optional true} ::sm/text]
    [:days-from-expiry {:optional true} ::sm/int]
    [:days-until-expiry {:optional true} ::sm/int]
    [:expiration-date {:optional true} schema:timestamp]]])

(defn- get-subscription-warning-api
  [cfg {:keys [penpot-id profile-id] :as params}]
  (let [penpot-id (or penpot-id profile-id)]
    (request-to-nitrate cfg :get
                        (generate-nitrate-uri "api/subscription-warning/" penpot-id)
                        schema:subscription-warning params)))

(defn- get-connectivity-api
  [cfg params]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri "api/connectivity")
                      schema:connectivity params))

(def ^:private schema:identity
  [:map
   [:nitrate-id ::sm/text]
   [:public-key ::sm/text]])

(defn- get-identity-api
  [cfg params]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri "api/identity")
                      schema:identity params))

(def ^:private schema:redeem-result
  [:map
   [:cancel-at [:maybe schema:timestamp]]])

(defn- get-organization-permissions-api
  [cfg {:keys [organization-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/organizations/"
                       organization-id
                       "permissions")
                      [:map
                       [:organization-id ::sm/uuid]
                       [:owner-id ::sm/uuid]
                       [:permissions [:map-of :keyword :string]]]
                      params))

(defn- get-organization-sso-api
  "Fetches the SSO configuration for an organization from Nitrate."
  [cfg {:keys [organization-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/organizations/"
                       organization-id
                       "sso")
                      schema:nitrate-sso
                      params))

(defn- get-organization-sso-by-team-api
  [cfg {:keys [team-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri "api/teams/" team-id "sso")
                      schema:nitrate-sso
                      params))

(defn- get-organization-members-api
  [cfg {:keys [organization-id] :as params}]
  (request-to-nitrate cfg :get
                      (generate-nitrate-uri
                       "api/organizations/"
                       organization-id
                       "members-list")
                      [:vector ::sm/uuid]
                      params))

(defn- redeem-activation-code-api
  [cfg params]
  (request-to-nitrate cfg :post
                      (generate-nitrate-uri "api/activation-codes/redeem")
                      schema:redeem-result
                      (assoc params :throw-on-error? true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::client
  [_ cfg]
  (when (contains? cf/flags :admin-console)
    {:get-team-organization                 (partial get-team-organization-api cfg)
     :set-team-organization                 (partial set-team-organization-api cfg)
     :get-organization-membership           (partial get-organization-membership-api cfg)
     :get-organization-membership-by-team   (partial get-organization-membership-by-team-api cfg)
     :get-organization-summary              (partial get-organization-summary-api cfg)
     :get-owned-organizations               (partial get-owned-organizations-api cfg)
     :get-owned-organizations-summary       (partial get-owned-organizations-summary-api cfg)
     :get-organization-members              (partial get-organization-members-api cfg)
     :cleanup-deleted-penpot-user  (partial cleanup-deleted-penpot-user-api cfg)
     :add-profile-to-organization           (partial add-profile-to-organization-api cfg)
     :remove-profile-from-organization      (partial remove-profile-from-organization-api cfg)
     :get-organization-permissions          (partial get-organization-permissions-api cfg)
     :get-organization-sso-by-team          (partial get-organization-sso-by-team-api cfg)
     :get-organization-sso                  (partial get-organization-sso-api cfg)
     :delete-team                  (partial delete-team-api cfg)
     :remove-team-from-organization         (partial remove-team-from-organization-api cfg)
     :get-subscription             (partial get-subscription-api cfg)
     :get-subscription-warning     (partial get-subscription-warning-api cfg)
     :connectivity                 (partial get-connectivity-api cfg)
     :get-identity                 (partial get-identity-api cfg)
     :redeem-activation-code       (partial redeem-activation-code-api cfg)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTILS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private team-organization-owner-cache
  ;; Short TTL: permission checks run on the read path, so we avoid an
  ;; HTTP call to nitrate per check. The organization owner of a team rarely
  ;; changes, and stale entries only grant read access for a few seconds.
  (cache/create :expire "30s" :max-size 2048))

(defn- nitrate-client?
  "True when `cfg` is a config map carrying the nitrate client (i.e. not
  a raw db connection/pool passed by an internal caller)."
  [cfg]
  (and (map? cfg) (some? (get cfg ::client))))

(def ^:private cache-miss ::no-organization-owner)

(defn- get-team-organization-owner-id
  "Returns the organization owner-id for `team-id`, or nil. Cached
  briefly, including negative results (teams with no organization) so
  repeated unauthorized probes don't each hit nitrate."
  [cfg team-id]
  (let [owner-id (cache/get team-organization-owner-cache team-id
                            (fn [team-id]
                              (let [team-with-organization (call cfg :get-team-organization {:team-id team-id})]
                                (or (get-in team-with-organization [:organization :owner-id])
                                    cache-miss))))]
    (when-not (= owner-id cache-miss)
      owner-id)))

(defn organization-owner-of-team?
  "True if `profile-id` is the owner of the organization that owns
  `team-id`. Used to grant non-member organization owners read-only access to the
  teams of their organizations. `cfg` must be a config map with the
  nitrate client; raw db connections/pools yield false so internal
  callers are unaffected. Returns false when the :nitrate flag is off."
  [cfg profile-id team-id]
  (boolean
   (when (and (contains? cf/flags :admin-console)
              (nitrate-client? cfg)
              (some? team-id)
              (some? profile-id))
     (= profile-id (get-team-organization-owner-id cfg team-id)))))

(defn sso-session-authorized?
  "Fetches the organization-SSO config for the given organization or team and checks
  whether the HTTP request has a valid session entry for it. Returns a map
  with :authorized and :sso keys."
  [cfg organization-id team-id request]
  (let [session (session/get-session request)
        sso     (if organization-id
                  (call cfg :get-organization-sso {:organization-id organization-id})
                  (call cfg :get-organization-sso-by-team {:team-id team-id}))]
    (if-not (:active sso)
      {:authorized true :sso sso}
      (if-not (str/blank? (:issuer sso))
        (let [props           (:props session)
              sso-map         (get props :sso {})
              organization-id (:organization-id sso)
              exp             (get sso-map organization-id)
              now             (ct/now)
              authorized      (and (ct/inst? exp)
                                   (ct/is-after? exp now))]
          {:authorized authorized :sso sso})
        {:authorized false :sso sso}))))

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

(defn add-organization-info-to-team
  "Enriches a team map with organization information from Nitrate.
  Adds organization-id, organization-name, organization-slug, organization-owner-id, and your-penpot fields.
  Returns the original team unchanged if the request fails or organization data is nil.
  Propagates `:nitrate-unavailable` so the request is rejected when Nitrate is unreachable."
  [cfg team params]
  (try
    (let [params        (assoc (or params {}) :team-id (:id team))
          team-with-organization (call cfg :get-team-organization params)
          organization           (:organization team-with-organization)]
      (if (some? organization)
        (-> (cto/apply-organization team (assoc organization :custom-photo
                                                (when-let [logo-id (:logo-id organization)]
                                                  (generate-public-uri "assets/by-id/" logo-id))))
            (assoc :is-default (or (:is-default team) (true? (:is-your-penpot team-with-organization)))))
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
        result (call cfg :set-team-organization params)]
    (when (nil? result)
      (ex/raise :type :internal
                :code :failed-to-set-team-organization
                :context {:team-id (:id team)
                          :organization-id (:organization-id params)}))
    team))
