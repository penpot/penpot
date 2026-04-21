;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.http
  "Helpers for intercepting and mocking the global `fetch` function in
  ClojureScript tests.  The underlying HTTP layer (`app.util.http`) calls
  `(js/fetch url params)` directly, so replacing `globalThis.fetch` is the
  correct interception point."
  (:require
   [app.common.transit :as t]
   [clojure.string :as str]))

(defn install-fetch-mock!
  "Replaces the global `js/fetch` with `handler-fn`.

  `handler-fn` is called with `[url opts]` where `url` is a plain string
  such as `\"http://localhost/api/main/methods/some-cmd\"`.  It must return
  a JS Promise that resolves to a fetch Response object.

  Returns the previous `globalThis.fetch` value so callers can restore it
  with [[restore-fetch!]]."
  [handler-fn]
  (let [prev (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis) handler-fn)
    prev))

(defn restore-fetch!
  "Restores `globalThis.fetch` to `orig` (the value returned by
  [[install-fetch-mock!]])."
  [orig]
  (set! (.-fetch js/globalThis) orig))

(defn make-json-response
  "Creates a minimal fetch `Response` that returns `body-clj` serialised as
  plain JSON with HTTP status 200."
  [body-clj]
  (let [json-str (.stringify js/JSON (clj->js body-clj))
        headers  (js/Headers. #js {"content-type" "application/json"})]
    (js/Response. json-str #js {:status 200 :headers headers})))

(defn make-transit-response
  "Creates a minimal fetch `Response` that returns `body-clj` serialised as
  Transit+JSON with HTTP status 200.  Use this helper when the code under
  test inspects typed values (UUIDs, keywords, etc.) from the response body,
  since the HTTP layer only decodes transit+json content automatically."
  [body-clj]
  (let [transit-str (t/encode-str body-clj {:type :json-verbose})
        headers     (js/Headers. #js {"content-type" "application/transit+json"})]
    (js/Response. transit-str #js {:status 200 :headers headers})))

(defn url->cmd
  "Extracts the RPC command keyword from a URL string.

  Example: `\"http://…/api/main/methods/create-upload-session\"`
  → `:create-upload-session`."
  [url]
  (when (string? url)
    (keyword (last (str/split url #"/")))))
