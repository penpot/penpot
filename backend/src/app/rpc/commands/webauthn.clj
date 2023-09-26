;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.webauthn
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.services :as sv]
   [buddy.core.nonce :as bn]
   [cuerdas.core :as str])
  (:import
   com.webauthn4j.WebAuthnManager
   com.webauthn4j.authenticator.Authenticator
   com.webauthn4j.authenticator.AuthenticatorImpl
   com.webauthn4j.converter.AttestedCredentialDataConverter
   com.webauthn4j.converter.util.ObjectConverter
   com.webauthn4j.data.AuthenticationData
   com.webauthn4j.data.AuthenticationParameters
   com.webauthn4j.data.AuthenticationRequest
   com.webauthn4j.data.RegistrationData
   com.webauthn4j.data.RegistrationParameters
   com.webauthn4j.data.RegistrationRequest
   com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
   com.webauthn4j.data.client.Origin
   com.webauthn4j.data.client.challenge.DefaultChallenge
   com.webauthn4j.server.ServerProperty))

(declare ^:private create-challenge!)
(declare ^:private get-current-challenge)
(declare ^:private prepare-registration-data)
(declare ^:private prepare-auth-data)
(declare ^:private validate-registration-data!)
(declare ^:private validate-auth-data!)
(declare ^:private get-attestation)
(declare ^:private update-passkey!)
(declare ^:private get-sign-count)
(declare ^:private get-profile)
(declare ^:private get-credentials)
(declare ^:private get-passkey)
(declare ^:private encode-attestation)
(declare ^:private decode-attestation)

(def ^:private manager
  (delay (WebAuthnManager/createNonStrictWebAuthnManager)))

;; TODO: output schema

(sv/defmethod ::prepare-profile-passkey-registration
  {::doc/added "1.20"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id]}]
  (db/with-atomic [conn pool]
    (let [cfg       (assoc cfg ::db/conn conn)
          profile   (get-profile cfg profile-id)
          challenge (create-challenge! cfg profile-id)
          uri       (u/uri (cf/get :public-uri))]
      {:challenge challenge
       :user-id (uuid/get-bytes profile-id)
       :user-email (:email profile)
       :user-name  (:fullname profile)
       :rp-id (:host uri)
       :rp-name "Penpot"})))

(def ^:private schema:create-profile-passkey
  [:map {:title "create-profile-passkey"}
   [:credential-id ::sm/bytes]
   [:attestation ::sm/bytes]
   [:client-data ::sm/bytes]])

(def ^:private schema:partial-passkey
  [:map {:title "PartilProfilePasskey"}
   [:id ::sm/uuid]
   [:created-at ::sm/inst]
   [:profile-id ::sm/uuid]])

(sv/defmethod ::create-profile-passkey
  {::sm/params schema:create-profile-passkey
   ::sm/result schema:partial-passkey
   ::doc/added "1.20"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id credential-id] :as params}]

  (db/with-atomic [conn pool]
    (let [cfg       (assoc cfg ::db/conn conn)
          challenge (get-current-challenge cfg profile-id)
          regdata   (prepare-registration-data params)]

      (validate-registration-data! regdata challenge)

      (let [attestation (get-attestation regdata)
            sign-count  (get-sign-count regdata)
            passkey  (db/insert! conn :profile-passkey
                                    {:id (uuid/next)
                                     :profile-id profile-id
                                     :credential-id credential-id
                                     :attestation attestation
                                     :sign-count sign-count})]
        (select-keys passkey [:id :created-at :profile-id])))))


;; FIXME: invitation token handling
(def ^:private schema:prepare-login-with-passkey
  [:map {:title "prepare-login-with-passkey"}
   [:email ::sm/email]])

(def ^:private schema:passkey-prepared-login
  [:map {:title "PasskeyPreparedLogin"}
   [:passkeys [:set ::sm/bytes]]
   [:challenge ::sm/bytes]])

(declare prepare-login-with-passkey)

(sv/defmethod ::prepare-login-with-passkey
  {::rpc/auth false
   ::doc/added "1.20"
   ::sm/params schema:prepare-login-with-passkey
   ::sm/result schema:passkey-prepared-login}
  [{:keys [::db/pool] :as cfg} {:keys [email]}]
  (db/with-atomic [conn pool]
    (let [cfg         (assoc cfg ::db/conn conn)
          profile     (get-profile cfg email)
          props       (:props profile)]

      (when (not= :all (:passkey props :all))
        (ex/raise :type :restriction
                  :code :passkey-disabled))

      (prepare-login-with-passkey cfg profile))))

(defn prepare-login-with-passkey
  [cfg {:keys [id] :as profile}]
  (let [credentials (get-credentials cfg id)
        challenge   (create-challenge! cfg id)
        uri         (u/uri (cf/get :public-uri))]

    {:credentials credentials
     :challenge challenge
     :rp-id (:host uri)}))

;; FIXME: invitation token handling
(def ^:private schema:login-with-passkey
  [:map {:title "login-with-passkey"}
   [:credential-id ::sm/bytes]
   [:user-handle [:maybe ::sm/bytes]]
   [:auth-data ::sm/bytes]
   [:client-data ::sm/bytes]])

(sv/defmethod ::login-with-passkey
  {::rpc/auth false
   ::doc/added "1.20"
   ::sm/params schema:login-with-passkey}
  [{:keys [::db/pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (let [cfg        (assoc cfg ::db/conn conn)
          passkey    (get-passkey cfg params)
          challenge  (get-current-challenge cfg (:profile-id passkey))
          authdata   (prepare-auth-data params)]

      (validate-auth-data! authdata passkey challenge)
      (update-passkey! cfg passkey authdata)

      (let [profile (->> (profile/get-profile conn (:profile-id passkey))
                         (profile/strip-private-attrs))]
        (-> profile
            (rph/with-transform (session/create-fn cfg (:id profile)))
            (rph/with-meta {::audit/props (audit/profile->props profile)
                            ::audit/profile-id (:id profile)}))))))

(sv/defmethod ::get-profile-passkeys
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id]}]
  (db/query pool :profile-passkey {:profile-id profile-id}
            {:columns [:id :profile-id :created-at :updated-at :sign-count]}))

(def ^:private schema:delete-profile-passkey
  [:map {:title "delete-profile-passkey"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-profile-passkey
  {::doc/added "1.20"
   ::sm/params schema:delete-profile-passkey}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (db/delete! pool :profile-passkey {:profile-id profile-id :id id})
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-challenge!
  [{:keys [::db/conn]} profile-id]
  (let [data (bn/random-nonce 32)
        sql  (dm/str "insert into profile_challenge values (?,?,now()) "
                     " on conflict (profile_id) "
                     " do update set data=?, created_at=now()")]
    (db/exec-one! conn [sql profile-id data data])
    data))

(defn- get-current-challenge
  [{:keys [::db/conn]} profile-id]
  (let [row (db/get conn :profile-challenge {:profile-id profile-id})]
    (DefaultChallenge. (:data row))))

(defn get-server-property
  [challenge]
  (let [uri  (cf/get :public-uri)
        host (-> uri u/uri :host)
        orig (Origin/create ^String uri)]
    (ServerProperty. ^Origin orig
                     ^String host
                     ^bytes challenge
                     nil)))

(defn- get-profile
  [{:keys [::db/conn]} email]
  (profile/decode-row
   (db/get* conn :profile
            {:email (str/lower email)}
            {:columns [:id :email :fullname :props]})))

(defn- get-credentials
  [{:keys [::db/conn]} profile-id]
  (->> (db/query conn :profile-passkey
                 {:profile-id profile-id}
                 {:columns [:credential-id]})
       (into #{} (map :credential-id))))

(defn- get-passkey
  [{:keys [::db/conn]} {:keys [credential-id user-handle]}]
  (let [params (cond-> {:credential-id credential-id}
                 (some? user-handle)
                 (assoc :profile-id (uuid/from-bytes user-handle)))]
    (db/get conn :profile-passkey params)))

(defn- update-passkey!
  [{:keys [::db/conn]} passkey ^AuthenticationData authdata]
  (let [credential-id (:credential-id passkey)
        sign-count    (.. authdata getAuthenticatorData getSignCount)]
    (db/update! conn :profile-passkey
                {:sign-count sign-count}
                {:credential-id credential-id})))

(defn- prepare-auth-data
  [{:keys [credential-id user-handle auth-data client-data signature] :as params}]
  (let [request (AuthenticationRequest. ^bytes credential-id
                                        ^bytes user-handle
                                        ^bytes auth-data
                                        ^bytes client-data
                                        nil
                                        ^bytes signature)]
    (.parse ^WebAuthnManager @manager
            ^AuthenticationRequest request)))

(defn- prepare-registration-data
  [{:keys [attestation client-data]}]
  (let [request (RegistrationRequest. attestation client-data)]
    (.parse ^WebAuthnManager @manager
            ^RegistrationRequest request)))

(defn- validate-registration-data!
  [regdata challenge]
  (let [property (get-server-property challenge)
        params   (RegistrationParameters. ^ServerProperty property false true)]
    (try
      (.validate ^WebAuthnManager @manager
                 ^RegistrationData regdata
                 ^RegistrationParameters params)
      (catch Throwable cause
        (ex/raise :type :validation
                  :code :webauthn-error
                  :cause cause)))))

(defn- get-authenticator
  [{:keys [attestation sign-count]}]
  (let [attestation (decode-attestation attestation)]
    (AuthenticatorImpl. ^AttestedCredentialData attestation nil ^long sign-count)))

(defn- validate-auth-data!
  [authdata passkey challenge]
  (let [property (get-server-property challenge)
        auth     (get-authenticator passkey)
        params   (AuthenticationParameters. ^ServerProperty property
                                            ^Authenticator auth
                                            nil
                                            false
                                            true)]
    (try
      (.validate ^WebAuthnManager @manager
                 ^AuthenticationData authdata
                 ^AuthenticationParameters params)
      (catch Throwable cause
        (l/err :hint "validation error on auth request" :cause cause)
        (ex/raise :type :validation
                  :code :webauthn-error
                  :cause cause)))))

(defn- get-attestation
  [^RegistrationData regdata]
  (encode-attestation
   (.. regdata
       (getAttestationObject)
       (getAuthenticatorData)
       (getAttestedCredentialData))))

(defn- get-sign-count
  [^RegistrationData regdata]
  (.. regdata
      (getAttestationObject)
      (getAuthenticatorData)
      (getSignCount)))

(defn- encode-attestation
  [attestation]
  (assert (instance? AttestedCredentialData attestation) "expected AttestedCredentialData instance")
  (let [converter (AttestedCredentialDataConverter. (ObjectConverter.))]
    (.convert converter ^AttestedCredentialData attestation)))

(defn- decode-attestation
  [attestation]
  (assert (bytes? attestation) "expected byte array")
  (let [converter (AttestedCredentialDataConverter. (ObjectConverter.))]
    (.convert converter ^bytes attestation)))
