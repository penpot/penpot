;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.nitrate
  "Internal Nitrate HTTP API.
     Provides authenticated access to organization management and token validation endpoints.

     All requests must include a valid shared key token in the Authorization header."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.access-token :refer [get-token]]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.worker :as-alias wrk]
   [integrant.core :as ig]
   [yetti.response :as-alias yres]))

;; ---- ROUTES

(declare ^:private authenticate)
(declare ^:private get-organization)
(declare ^:private create-organization)
(declare ^:private update-organization)

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(def ^:private auth
  {:name ::auth
   :compile
   (fn [_ _]
     (fn [handler shared-key]
       (if shared-key
         (fn [request]
           (let [token (get-token request)]
             (if (= token shared-key)
               (handler request)
               {::yres/status 403})))
         (fn [_ _]
           {::yres/status 403}))))})

(def ^:private default-system
  {:name ::default-system
   :compile
   (fn [_ _]
     (fn [handler cfg]
       (fn [request]
         (handler cfg request))))})

(def ^:private transaction
  {:name ::transaction
   :compile
   (fn [data _]
     (when (:transaction data)
       (fn [handler]
         (fn [cfg request]
           (db/tx-run! cfg handler request)))))})

(defmethod ig/init-key ::routes
  [_ cfg]
  ["" {:middleware [[auth (cf/get :nitrate-api-shared-key)]
                    [default-system cfg]
                    [transaction]]}
   ["/authenticate"
    {:handler authenticate
     :allowed-methods #{:post}}]

   ["/get-organization"
    {:handler get-organization
     :allowed-methods #{:post}
     :transaction true}]

   ["/update-organization"
    {:handler update-organization
     :allowed-methods #{:post}
     :transaction true}]

   ["/create-organization"
    {:handler create-organization
     :allowed-methods #{:post}
     :transaction true}]])

;; ---- HELPERS

(defn- coercer
  "Returns a parameter coercion function that:
   - Decodes JSON params according to the given `schema`
   - Validates them using `sm/check-fn`
   - Throws validation errors if data is invalid

   @param schema  Schema definition for input validation
   @return function that validates request params"
  [schema & {:as opts}]
  (let [decode-fn (sm/decoder schema sm/json-transformer)
        check-fn  (sm/check-fn schema opts)]
    (fn [data]
      (-> data decode-fn check-fn))))


(defn with-current-user
  "Wraps a handler to inject the current user ID if the provided token is valid.
   Returns 403 if no valid user is found or token verification fails.

   @param handler - function of [cfg request current-user-id]
   @return handler of [cfg request]"
  [handler]
  (fn [cfg request]
    (let [token (-> request :params :user-token)
          auth  (when token (tokens/verify cfg {:token token :iss "authentication"}))
          uid   (:uid auth)]
      (if uid
        (handler cfg request uid)
        {::yres/status 403}))))

(def ^:private sql:get-role
  "SELECT role
     FROM organization_profile_rel
    WHERE organization_id=?
      and profile_id=?;")

(defn- get-role
  [cfg organization_id profile-id]
  (let [result (db/exec-one! cfg [sql:get-role organization_id profile-id])]
    (:role result)))

;; ---- API: AUTHENTICATE

(defn- authenticate
  "Authenticate a service token.

   @api POST /authenticate
   @auth SharedKey
   @params
     token (string): The access token to validate.
   @returns
     200 OK: Returns decoded token claims if valid.
     403 Forbidden: If the shared key or token is invalid."
  [cfg request]
  (let [token  (-> request :params :token)
        result (tokens/verify cfg {:token token :iss "authentication"})]
    {::yres/status 200
     ::yres/body result}))

;; ---- API: GET-ORGANIZATION

(def ^:private schema:get-organization
  [:map [:id ::sm/uuid]])

(def ^:private coerce-get-organization-params
  (coercer schema:get-organization
           :type :validation
           :hint "invalid data provided for `get-organization` rpc call"))

(def get-organization
  "Retrieve an organization by ID.

   @api POST /get-organization
   @auth SharedKey
   @params
     id (uuid): Organization identifier.
   @returns
     200 OK: Returns the organization record.
     400 Bad Request: Invalid input data.
     403 Forbidden: If the shared key or user token is invalid.
     404 Not Found: Organization not found."
  (with-current-user
    (fn [cfg request _]
      (let [organization-id (-> request :params coerce-get-organization-params :id)
            result          (db/get-by-id cfg :organization organization-id)]
        {::yres/status 200
         ::yres/body result}))))



;; ---- API: CREATE-ORGANIZATION

(def ^:private schema:create-organization
  [:map
   [:name [::sm/word-string {:max 250}]]])

(def ^:private coerce-create-organization-params
  (coercer schema:create-organization
           :type :validation
           :hint "invalid data provided for `create-organization` rpc call"))

(def create-organization
  "Create a new organization.

   @api POST /create-organization
   @auth SharedKey
   @params
     name (string, max 250): Name of the organization.
   @returns
     201 Created: Returns the newly created organization.
     400 Bad Request: Invalid data."
  (with-current-user
    (fn [cfg request current-user-id]
      (let [{:keys [name]}
            (-> request :params coerce-create-organization-params)]

        (l/dbg :hint "create organization"
               :name name)

        (let [organization (db/insert! cfg :organization  {:id (uuid/next) :name name})
              _ (db/insert! cfg :organization_profile_rel {:organization_id (:id organization) :profile_id current-user-id :role :owner})]
          {::yres/status 201
           ::yres/body organization})))))

;; ---- API: UPDATE-ORGANIZATION

(def ^:private schema:update-organization
  [:map
   [:id ::sm/uuid]
   [:name [::sm/word-string {:max 250}]]])

(def ^:private coerce-update-organization-params
  (coercer schema:update-organization
           :type :validation
           :hint "invalid data provided for `update-organization` rpc call"))

(def update-organization
  "Update an existing organization’s name.

   @api POST /update-organization
   @auth SharedKey
   @params
     id (uuid): Organization identifier.
     name (string, max 250): New organization name.
   @returns
     201 Updated: Operation successful.
     400 Bad Request: Invalid input data.
     401 Unauthorized: The user is not authenticated (missing or invalid credentials).
     403 Forbidden: Insufficient permissions."
  (with-current-user
    (fn [cfg request current-user-id]
      (let [{:keys [id name]}
            (-> request :params coerce-update-organization-params)

            organization (db/get-by-id cfg :organization id)

            role         (when organization (get-role cfg id current-user-id))]

        (if (= (keyword role) :owner)
          (do
            (l/dbg :hint "update organization"
                   :id (str id)
                   :name name)

            (db/update! cfg :organization
                        {:name name}
                        {:id id}
                        {::db/return-keys false})

            {::yres/status 201
             ::yres/body nil})
          {::yres/status 403
           ::yres/body nil})))))
