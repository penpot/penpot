;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.mcp.oauth-clients
  "Client metadata from self-contained registrations or HTTPS documents.
  Registration describes an application; it does not authorize a Penpot user.
  Both sources resolve to the same metadata shape without a client database."
  (:require
   [app.http.client :as http]
   [app.mcp.oauth-state :as state]
   [app.util.cache :as cache]
   [app.util.json :as json]
   [clojure.string :as str])
  (:import
   java.io.InputStream
   java.net.URI
   java.util.Base64))

(defn invalid!
  ([error] (invalid! error 400))
  ([error status]
   (throw (ex-info "MCP OAuth request rejected" {:oauth-error error :status status}))))

(defn uri
  [value]
  (when (and (string? value) (<= 1 (count value) 2048))
    (try (URI. value) (catch Exception _ nil))))

(defn- loopback?
  [^URI uri]
  (and (= "http" (.getScheme uri))
       (contains? #{"127.0.0.1" "[::1]" "localhost"} (.getHost uri))))

(defn redirect-uri?
  [value]
  (when-let [uri (uri value)]
    (and (nil? (.getFragment uri))
         (nil? (.getUserInfo uri))
         (or (and (= "https" (.getScheme uri)) (some? (.getHost uri)))
             (loopback? uri)
             ;; Native applications use a reverse-domain scheme.
             (and (some? (.getScheme uri))
                  (str/includes? (.getScheme uri) ".")
                  (not (.isOpaque uri)))))))

(defn redirect-matches?
  "Exact callback match, except for the port of loopback HTTP URLs.
  Native applications may allocate a different local port for each login."
  [registered requested]
  (and (redirect-uri? requested)
       (some (fn [value]
               (or (= value requested)
                   (let [a (uri value)
                         b (uri requested)]
                     (and (loopback? a) (loopback? b)
                          (= (.getHost a) (.getHost b))
                          (= (.getRawPath a) (.getRawPath b))
                          (= (.getRawQuery a) (.getRawQuery b))))))
             (:redirect_uris registered))))

(defn- logo-uri?
  [value]
  (when-let [uri (uri value)]
    (and (= "https" (.getScheme uri))
         (some? (.getHost uri))
         (nil? (.getUserInfo uri))
         (nil? (.getFragment uri)))))

(defn validate-metadata
  "Validate untrusted metadata and retain the fields this server supports.
  CIMD selects public-client authentication from the advertised methods;
  dynamic registration also supports Basic and form-post client secrets."
  [metadata cimd?]
  (let [redirects (:redirect_uris metadata)
        grants    (get metadata :grant_types ["authorization_code"])
        methods   (if cimd?
                    (or (:token_endpoint_auth_methods_supported metadata)
                        [(get metadata :token_endpoint_auth_method "none")])
                    [(get metadata :token_endpoint_auth_method "client_secret_basic")])
        method    (if cimd? "none" (first methods))]
    (when-not (and (map? metadata)
                   (vector? redirects) (<= 1 (count redirects) 20)
                   (every? redirect-uri? redirects)
                   (or (nil? (:client_name metadata))
                       (and (string? (:client_name metadata))
                            (<= 1 (count (:client_name metadata)) 200)))
                   ;; Published metadata describes client capabilities across
                   ;; servers. Extra grants do not require us to issue them.
                   (if cimd?
                     (and (vector? grants) (every? string? grants)
                          (some #{"authorization_code"} grants))
                     (= ["authorization_code"] grants))
                   (= ["code"] (get metadata :response_types ["code"]))
                   (or (nil? (:scope metadata)) (= "mcp" (:scope metadata)))
                   (if cimd?
                     (and (vector? methods) (some #{"none"} methods))
                     (contains? #{"none" "client_secret_basic" "client_secret_post"} method)))
      (invalid! "invalid_client_metadata"))
    (cond-> {:redirect_uris redirects
             :grant_types ["authorization_code"]
             :response_types ["code"]
             :scope "mcp"
             :token_endpoint_auth_method method}
      (:client_name metadata) (assoc :client_name (:client_name metadata))
      ;; Logos are optional browser-loaded images. Ignore unusable URLs
      ;; instead of preventing authorization because of cosmetic metadata.
      (logo-uri? (:logo_uri metadata)) (assoc :logo_uri (:logo_uri metadata)))))

(defn register!
  "Issue a reusable client ID and, when applicable, a separate client secret.
  No client record is stored. The public ID carries only the secret hash;
  the random nonce distinguishes registrations with otherwise equal metadata."
  [_cfg metadata]
  (let [metadata (validate-metadata metadata false)
        secret   (when-not (= "none" (:token_endpoint_auth_method metadata))
                   (state/random-secret))
        ;; Omit fixed protocol values to keep IDs short; validation restores
        ;; them on resolution. The version prefix defines the payload format.
        payload  (cond-> (-> metadata
                             (dissoc :grant_types :response_types :scope)
                             (assoc :nonce (state/random-secret)))
                   secret (assoc :secret-hash (state/digest secret)))
        id       (str "penpot-mcp-v1."
                      (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                       (json/encode payload)))]
    ;; Enforce the resolver's size cap here rather than issue an unusable ID.
    (when (> (count id) 2048)
      (invalid! "invalid_client_metadata"))
    (cond-> (assoc metadata :client_id id
                   :client_id_issued_at (quot (System/currentTimeMillis) 1000))
      secret (assoc :client_secret secret :client_secret_expires_at 0))))

(defn- decode-client-id
  [client-id]
  ;; Open registration accepts client-supplied metadata, not a trusted identity.
  ;; Changing these details changes the ID; codes are bound to the exact ID
  ;; approved by the user, so an edited ID cannot redeem an existing code.
  (try
    (let [[_ encoded] (re-matches #"penpot-mcp-v1\.([A-Za-z0-9_-]+)" client-id)
          _           (when-not encoded (invalid! "invalid_client"))
          payload     (json/decode (.decode (Base64/getUrlDecoder) ^String encoded))
          metadata    (validate-metadata payload false)
          nonce       (:nonce payload)
          secret-hash (:secret-hash payload)]
      (when-not (and (string? nonce) (re-matches #"[A-Za-z0-9_-]{43}" nonce)
                     (if (= "none" (:token_endpoint_auth_method metadata))
                       (nil? secret-hash)
                       (and (string? secret-hash)
                            (re-matches #"[A-Za-z0-9_-]{43}" secret-hash))))
        (invalid! "invalid_client"))
      (cond-> (assoc metadata :client_id client-id)
        secret-hash (assoc :secret-hash secret-hash)))
    (catch Exception _
      (invalid! "invalid_client"))))

(defonce ^:private metadata-cache
  (cache/create :expire "1m" :max-size 256))

(defn- fetch-metadata
  "Fetch a client's own HTTPS metadata document with bounded size and freshness.
  The shared HTTP client checks SSRF targets and does not follow redirects."
  [cfg client-id]
  (let [response (http/req cfg {:method :get :uri client-id :timeout 5000
                                :headers {"accept" "application/json"}}
                           {:response-type :input-stream})]
    (with-open [^InputStream body (:body response)]
      (when-not (and (= 200 (:status response))
                     (str/starts-with? (get-in response [:headers "content-type"] "")
                                       "application/json"))
        (invalid! "invalid_client"))
      ;; Bound both bytes read and time spent receiving the response body.
      (let [reader (future (.readNBytes body 65537))
            bytes  (deref reader 5000 ::timeout)]
        (when (= ::timeout bytes)
          (future-cancel reader)
          (invalid! "invalid_client"))
        (when (> (alength ^bytes bytes) 65536)
          (invalid! "invalid_client"))
        (let [metadata (json/decode bytes)
              control  (get-in response [:headers "cache-control"] "")
              ;; The local cache ceiling never extends the publisher's TTL.
              ;; Missing cache lifetime is treated as immediately stale.
              max-age  (some-> (re-find #"(?i)max-age=(\d+)" control) second parse-long)
              ttl      (if (re-find #"(?i)no-store|no-cache" control)
                         0
                         (min 60 (or max-age 0)))]
          (when-not (= client-id (:client_id metadata))
            (invalid! "invalid_client"))
          {:client (assoc (validate-metadata metadata true) :client_id client-id)
           :expires (+ (System/currentTimeMillis) (* ttl 1000))})))))

(defn resolve-client
  "Resolve a client ID to validated metadata for authorization or exchange.
  HTTPS IDs use the document cache; versioned IDs decode locally without
  PostgreSQL or Redis. Neither form establishes user approval."
  [cfg client-id]
  (when-not (and (string? client-id) (<= 1 (count client-id) 2048))
    (invalid! "invalid_client"))
  (if-let [url (when (str/starts-with? client-id "https://") (uri client-id))]
    (do
      (when-not (and (.getHost url)
                     (nil? (.getUserInfo url))
                     (nil? (.getFragment url))
                     (not (str/blank? (.getPath url))))
        (invalid! "invalid_client"))
      (try
        (let [cached (cache/get metadata-cache client-id)
              entry  (if (and cached (> (:expires cached) (System/currentTimeMillis)))
                       cached
                       (fetch-metadata cfg client-id))]
          (when-not (identical? cached entry)
            (cache/invalidate metadata-cache client-id)
            (cache/get metadata-cache client-id (constantly entry)))
          (:client entry))
        (catch Exception _
          (invalid! "invalid_client"))))
    (decode-client-id client-id)))
