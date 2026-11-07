;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.mcp.oauth-state
  "Temporary approval requests and authorization codes in Redis/Valkey.
  Handles are opaque; tenant, record kind and handle hash form the key.
  Mutations compare the exact record that the HTTP handler validated."
  (:require
   [app.config :as cf]
   [app.redis :as rds]
   [app.redis.script :as-alias script]
   [app.util.json :as json])
  (:import
   java.nio.charset.StandardCharsets
   java.security.MessageDigest
   java.security.SecureRandom
   java.util.Base64))

(defn random-secret
  "32 random bytes encoded as a 43-character, unpadded base64url string."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn digest
  "Unpadded base64url SHA-256 digest, also used for PKCE S256 challenges."
  [^String value]
  (-> (.digest (MessageDigest/getInstance "SHA-256")
               (.getBytes value StandardCharsets/UTF_8))
      (#(.encodeToString (.withoutPadding (Base64/getUrlEncoder)) %))))

(defn secret=
  [a b]
  (and (string? a) (string? b)
       (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8)
                              (.getBytes ^String b StandardCharsets/UTF_8))))

(defn- state-key
  [kind handle]
  (str "penpot:" (cf/get :tenant) ":mcp-oauth:" (name kind) ":" (digest handle)))

(defn create!
  "Store a new record for ttl seconds and return its random handle.
  The handle is returned only after Redis confirms SET NX EX succeeded."
  [cfg kind value ttl]
  (let [handle (random-secret)
        result (rds/run! cfg
                         (fn [{::rds/keys [conn]}]
                           (rds/set conn (state-key kind handle)
                                    (json/encode-str value) {:ex ttl :nx true})))]
    (when-not (= "OK" result)
      (throw (ex-info "Unable to store OAuth state" {:oauth-error "temporarily_unavailable"})))
    handle))

(defn read!
  "Read an unexpired record, or nil for a missing/malformed handle.
  Return both decoded :value for validation and original :raw for atomic
  comparison; serializing the decoded map again is not an equivalent check."
  [cfg kind handle]
  (when (and (string? handle) (re-matches #"[A-Za-z0-9_-]{43}" handle))
    (when-let [raw (rds/run! cfg (fn [{::rds/keys [conn]}]
                                   (rds/get conn (state-key kind handle))))]
      {:raw raw :value (json/decode raw)})))

(defn- compare-and-update!
  "Atomically change only the exact record the caller read and validated.
  Lua deletes for nil replacement, or replaces while preserving the TTL.
  False means the record expired or another request changed/consumed it."
  [cfg kind handle record replacement]
  (let [result (rds/run! cfg
                         (fn [{::rds/keys [conn]}]
                           (rds/eval conn
                                     {::script/name ::compare-and-update
                                      ::script/path "app/mcp/oauth_state.lua"
                                      ::script/keys [(state-key kind handle)]
                                      ::script/vals [(:raw record) (or replacement "")]})))]
    (= [1] result)))

(defn consume!
  "Delete a validated record once; true only for the request that consumes it."
  [cfg kind handle record]
  (compare-and-update! cfg kind handle record nil))

(defn replace!
  "Replace a validated record without extending its original expiration."
  [cfg kind handle record value]
  (compare-and-update! cfg kind handle record (json/encode-str value)))

(defn allow-request!
  "Limit requests per endpoint kind, tenant and remote address.
  The one-minute window begins with the first request; subsequent requests
  increment the count without extending that window."
  [cfg kind remote-address limit]
  (let [[count] (rds/run! cfg
                          (fn [{::rds/keys [conn]}]
                            (rds/eval conn
                                      {::script/name ::rate
                                       ::script/path "app/mcp/oauth_rate.lua"
                                       ::script/keys [(state-key kind (or remote-address "unknown"))]
                                       ::script/vals [60]})))]
    (and (number? count) (<= count limit))))
