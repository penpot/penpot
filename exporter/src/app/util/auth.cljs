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
