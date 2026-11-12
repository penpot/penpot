;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.location
  "The app URL as plugins see it: the instance base URL and the current
  route's query params, without the params that carry secrets."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.router :as rt]))

(def ^:private secret-params
  "Query params that grant access or carry auth state."
  #{:share-id :request :code :state})

(defn- secret-param?
  [k]
  (or (contains? secret-params k)
      (some? (re-find #"(?i)(token|secret|password)$" (name k)))))

(defn- public-params
  "Return the current route's query params without secrets. A key
  repeated in the URL keeps its last value, as the app reads it."
  [state]
  (let [params (rt/get-params state)]
    (into {}
          (comp (remove (comp secret-param? key))
                (map (fn [[k _]] [k (rt/get-query-param params k)])))
          params)))

(defn location
  "Return `{:baseUrl :href :params}` for the current route in `state`.
  `href` is rebuilt from the public params, so it never carries a secret."
  [state]
  (let [base-url (dm/str cf/public-uri)
        params   (public-params state)]
    {:baseUrl base-url
     :href     (if (empty? params)
                 base-url
                 (dm/str base-url "?" (u/map->query-string params)))
     :params   (update-keys params name)}))
