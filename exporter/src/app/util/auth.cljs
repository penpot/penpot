;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.auth
  "Helpers for forwarding the caller's auth to the backend.")

(defn header-value
  "Builds the `Authorization` header value to forward to the backend for a
  given token + scheme. Penpot access tokens use the `Token` scheme; session
  tokens (incl. those resolved from a cookie) use `Bearer`. Defaults to
  `Bearer` so existing callers are unaffected."
  ([token] (header-value token :bearer))
  ([token scheme]
   (str (if (= scheme :token) "Token " "Bearer ") token)))

(defn asset-headers
  "Auth headers for fetching `/assets/*` URLs. Those are served through the
  storage backend: nginx follows an internal redirect to a *presigned* S3/minio
  URL and forwards the client's headers, so a `Bearer` Authorization header
  makes S3 reject the request with 'multiple authentication types' (400).
  Session tokens are therefore forwarded the way the browser sends them — as
  the auth cookie — and access tokens keep the `Token` scheme, which S3 does
  not parse as an authentication type."
  [token scheme]
  (if (= scheme :token)
    {"Authorization" (str "Token " token)}
    {"Cookie" (str "auth-token=" token)}))
