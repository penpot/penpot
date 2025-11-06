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
   [app.msgbus :as mbus]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.worker :as-alias wrk]
   [clojure.walk :as walk]
   [integrant.core :as ig]
   [yetti.response :as-alias yres]))

;; ---- ROUTES

(declare ^:private authenticate)
(declare ^:private get-organization)
(declare ^:private create-organization)
(declare ^:private update-organization)
(declare ^:private list-organizations)
(declare ^:private list-teams)
(declare ^:private set-team-org)


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
               {::yres/status 401})))
         (fn [_ _]
           {::yres/status 401}))))})

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

   ["/list-organizations"
    {:handler list-organizations
     :allowed-methods #{:post}
     :transaction true}]

   ["/list-teams"
    {:handler list-teams
     :allowed-methods #{:post}
     :transaction true}]

   ["/set-team-org"
    {:handler set-team-org
     :allowed-methods #{:post}
     :transaction true}]

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
   Returns 401 if no valid user is found or token verification fails.

   @param handler - function of [cfg request current-user-id]
   @return handler of [cfg request]"
  [handler]
  (fn [cfg request]
    (let [token (-> request :params :user-token)
          auth  (when token (tokens/verify cfg {:token token :iss "authentication"}))
          uid   (:uid auth)]
      (if uid
        (handler cfg request uid)
        {::yres/status 401}))))

(def ^:private sql:get-role
  "SELECT role
     FROM organization_profile_rel
    WHERE organization_id = ?
      and profile_id = ?;")

(defn- is-org-owner?
  [cfg organization-id current-user-id]
  (let [result (db/exec-one! cfg [sql:get-role organization-id current-user-id])]
    (= "owner" (:role result))))

(def ^:private sql:is-team-owner
  "SELECT COUNT(*) AS n
     FROM team_profile_rel
    WHERE team_id = ?
      AND profile_id = ?
      AND is_owner = true;")

(defn- is-team-owner?
  [cfg team-id current-user-id]
  (-> (db/exec-one! cfg [sql:is-team-owner team-id current-user-id])
      :n
      pos?))

;; ---- API: AUTHENTICATE

(defn- authenticate
  "Authenticate a service token.

   @api POST /authenticate
   @auth SharedKey
   @params
     token (string): The access token to validate.
   @returns
     200 OK: Returns decoded token claims if valid.
     401 Unauthorized: If the shared key or token is invalid."
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
     401 Unauthorized: If the shared key or user token is invalid.
     404 Not Found: Organization not found."
  (with-current-user
    (fn [cfg request _]
      (let [organization-id (-> request :params coerce-get-organization-params :id)
            result          (-> (db/get-by-id cfg :organization organization-id)
                                (walk/stringify-keys))]
        {::yres/status 200
         ::yres/body result}))))

;; ---- API: LIST-ORGANIZATIONS

(def ^:private sql:list-organizations
  "SELECT o.*
     FROM organization AS o
     JOIN organization_profile_rel AS opr ON o.id = opr.organization_id
    WHERE opr.profile_id = ?
      AND opr.role = 'owner';")


(def list-organizations
  "List organizations for which current user is owner.

   @api POST /list-organizations
   @auth SharedKey
   @returns
     200 OK: Returns the list of organizations for the user.
     401 Unauthorized: If the shared key or user token is invalid."
  (with-current-user
    (fn [cfg _request current-user-id]
      (let [result (->> (db/exec! cfg [sql:list-organizations current-user-id])
                        (map #(update % :id str))
                        vec
                        walk/stringify-keys)]
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
     400 Bad Request: Invalid data.
     401 Unauthorized: If the shared key or user token is invalid."
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
     204 Updated: Operation successful.
     400 Bad Request: Invalid input data.
     401 Unauthorized: If the shared key or user token is invalid.
     403 Forbidden: The user doesn't have permissions to execute this operation."
  (with-current-user
    (fn [cfg request current-user-id]
      (let [{:keys [id name]}
            (-> request :params coerce-update-organization-params)
            org-owner?   (is-org-owner? cfg id current-user-id)]

        (if org-owner?
          (do
            (db/update! cfg :organization
                        {:name name}
                        {:id id}
                        {::db/return-keys false})

            {::yres/status 204
             ::yres/body nil})
          {::yres/status 403
           ::yres/body nil})))))

;; ---- API: LIST-TEAMS

(def ^:private sql:list-teams
  "SELECT t.*
     FROM team AS t
     JOIN team_profile_rel AS tpr ON t.id = tpr.team_id
    WHERE tpr.profile_id = ?
      AND tpr.is_owner = 't'
      AND t.is_default = 'f';")


(def list-teams
  "List teams for which current user is owner.

   @api POST /list-teams
   @auth SharedKey
   @returns
     200 OK: Returns the list of teams for the user.
     401 Unauthorized: If the shared key or user token is invalid."
  (with-current-user
    (fn [cfg _request current-user-id]
      (let [result (->> (db/exec! cfg [sql:list-teams current-user-id])
                        (map #(dissoc % :features :subscription :is-default))
                        (map #(update % :organization-id str))
                        (map #(update % :id str))
                        vec
                        walk/stringify-keys)]
        {::yres/status 200
         ::yres/body result}))))


;; ---- API: SET-TEAM-ORG

(def ^:private schema:set-team-org
  [:map
   [:team-id ::sm/uuid]
   [:organization-id {:optional true} [:maybe ::sm/uuid]]])

(def ^:private coerce-set-team-org-params
  (coercer schema:set-team-org
           :type :validation
           :hint "invalid data provided for `set-team-org` rpc call"))

(def ^:private sql:set-team-org
  "UPDATE team
      SET organization_id = ?
      WHERE team.id = ?;")


(def set-team-org
  "Set the organization of a team.

   @api POST /set-team-org
   @auth SharedKey
   @params
     team-id (uuid): Team identifier.
     organization-id (uuid | null): Organization identifier, or null to remove association.
   @returns
     204 Updated: Operation successful.
     401 Unauthorized: If the shared key or user token is invalid.
     403 Forbidden: The user doesn't have permissions to execute this operation."
  (with-current-user
    (fn [cfg request current-user-id]
      (let [{:keys [organization-id team-id]}
            (-> request :params coerce-set-team-org-params)
            org-owner?   (if (nil? organization-id)
                           true
                           (is-org-owner? cfg organization-id current-user-id))

            team-owner?  (is-team-owner? cfg team-id current-user-id)
            organization (when (and team-owner? org-owner?) (db/get-by-id cfg :organization organization-id))
            msgbus (::mbus/msgbus cfg)]


        (if (or
             (not team-owner?)
             (not org-owner?))
          {::yres/status 403}
          (do
            (db/exec! cfg [sql:set-team-org organization-id team-id])
            (mbus/pub! msgbus
                       :topic uuid/zero #_team-id
                       :message {:type :team-org-change
                                 :team-id team-id
                                 :organization-id organization-id
                                 :organization-name (:name organization)})
            {::yres/status 204}))))))