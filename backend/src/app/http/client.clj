;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.client
  "Http client abstraction layer.

   All outbound requests made through `req` and `req-with-redirects`
   are validated against the SSRF blocklist by default. Pass
   `:skip-ssrf-check? true` in the options map only when the target
   is a well-known, operator-configured endpoint that cannot be
   influenced by user input (e.g. internal telemetry, error webhooks)."
  (:require
   [app.common.schema :as sm]
   [app.util.ssrf :as ssrf]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [java-http-clj.core :as http])
  (:import
   java.net.http.HttpClient
   java.net.URI))

(def default-max-redirects 5)

(defn client?
  [o]
  (instance? HttpClient o))

(sm/register!
 {:type ::client
  :pred client?})

(defmethod ig/init-key ::client
  [_ _]
  (http/build-client {:connect-timeout 30000
                      :follow-redirects :never}))

(defn send!
  ([client req] (send! client req {}))
  ([client req {:keys [response-type] :or {response-type :string}}]
   (assert (client? client) "expected valid http client")
   (http/send req {:client client :as response-type})))

(defn- resolve-client
  [params]
  (cond
    (instance? HttpClient params)
    params

    (map? params)
    (resolve-client (::client params))

    :else
    (throw (UnsupportedOperationException. "invalid arguments"))))

(defn req
  "Issue a single HTTP request.  SSRF validation is applied to the
   target URI by default; pass `:skip-ssrf-check? true` in `options`
   to bypass it for known-safe, operator-configured endpoints."
  ([cfg-or-client request]
   (req cfg-or-client request {}))
  ([cfg-or-client request {:keys [skip-ssrf-check?] :as options}]
   (let [request (if skip-ssrf-check?
                   (update request :uri str)
                   (update request :uri ssrf/validate-uri))
         client  (resolve-client cfg-or-client)]
     (send! client request (dissoc options :skip-ssrf-check?)))))

(defn- resolve-location
  "Resolve a Location header value against the original request URI.
   Handles:
     - Absolute URLs (http:// or https://) — returned as-is.
     - Protocol-relative URLs (//host/path) — inherit the scheme from base-uri.
     - Path-absolute and relative URLs — resolved against base-uri via URI.resolve."
  [^String base-uri ^String location]
  (cond
    (or (str/starts-with? location "http://")
        (str/starts-with? location "https://"))
    location

    (str/starts-with? location "//")
    (let [scheme (.getScheme (URI. base-uri))]
      (str scheme ":" location))

    :else
    (str (.resolve (URI. base-uri) location))))

(defn- redirect-request
  "Build the next request for a 3xx redirect.
   Per RFC 7231 §6.4:
     - 303 always issues GET (body dropped).
     - 301/302 with non-GET/HEAD methods: downgrade to GET (body dropped).
     - 307/308 preserve the original method and body.
   The Location URI has already been resolved by the caller."
  [orig-request ^String next-uri status]
  (let [method (:method orig-request)]
    (if (or (= status 303)
            (and (contains? #{301 302} status)
                 (not (contains? #{:get :head} method))))
      ;; Downgrade to GET, drop body and content-type
      (-> orig-request
          (assoc :uri next-uri :method :get)
          (dissoc :body)
          (update :headers dissoc "content-type" "content-length"))
      ;; Preserve method/body (307, 308, or GET/HEAD 301/302)
      (assoc orig-request :uri next-uri))))

(defn req-with-redirects
  "Like `req`, but follows up to `max-redirects` HTTP 3xx redirects.
   SSRF validation is applied before every hop (initial request and
   each redirect target) unless `:skip-ssrf-check? true` is passed.
   Redirect semantics follow RFC 7231 §6.4: 301/302 POST is downgraded
   to GET; 303 always uses GET; 307/308 preserve the original method."
  ([cfg-or-client request]
   (req-with-redirects cfg-or-client request {}))
  ([cfg-or-client request {:keys [max-redirects skip-ssrf-check?]
                           :or   {max-redirects default-max-redirects}
                           :as opts}]
   (let [send-opts  (dissoc opts :max-redirects :skip-ssrf-check?)
         uri-coerce (if skip-ssrf-check? str ssrf/validate-uri)]
     (loop [current-req (update request :uri uri-coerce)
            hops        0]
       (let [client (resolve-client cfg-or-client)
             resp   (send! client current-req send-opts)
             status (:status resp)]
         (if (and (<= 300 status 399)
                  (< hops max-redirects))
           (if-let [location (get-in resp [:headers "location"])]
             (let [next-uri (resolve-location (str (:uri current-req)) location)]
               (recur (update (redirect-request current-req next-uri status) :uri uri-coerce)
                      (inc hops)))
             ;; No Location header on a 3xx — return the response as-is
             resp)
           resp))))))
