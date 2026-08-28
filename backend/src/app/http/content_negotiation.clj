;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.content-negotiation
  "Content format negotiation helpers shared between the regular
  response formatting middleware and the SSE streaming endpoints.

  The negotiated format affects only the encoding of the response
  payload; the transport (regular http body or `text/event-stream`)
  is not affected."
  (:require
   [app.common.json :as json]
   [app.util.pointer-map :as pmap]
   [cuerdas.core :as str]
   [yetti.request :as yreq]))

(defn- format-from-params
  [{:keys [query-params]}]
  (and (= "json" (get query-params :_fmt))
       :json))

(defn negotiate-format
  "Determine the response payload format for the request. Returns
  `:json` or `:transit`.

  The `:_fmt=json` query parameter takes precedence over the
  `Accept` header; when no explicit signal is present, transit is
  the default."
  [request]
  (or (format-from-params request)
      (let [accept (yreq/get-header request "accept")]
        (cond
          (or (= accept "application/transit+json")
              (str/includes? accept "application/transit+json"))
          :transit

          (or (= accept "application/json")
              (str/includes? accept "application/json"))
          :json

          :else
          :transit))))

(defn write-json-value
  [_ val]
  (if (pmap/pointer-map? val)
    [(pmap/get-id val) (meta val)]
    val))

(defn json-encode-str
  "Encode value as a JSON string using the same conventions as the
  regular JSON API responses: camelCase keys, keywords/UUIDs and
  instants serialized as plain JSON scalars."
  [v]
  (json/encode v :key-fn json/write-camel-key :value-fn write-json-value))
