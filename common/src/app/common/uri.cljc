;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.uri
  (:refer-clojure :exclude [uri?])
  (:require
   [app.common.data :as d]
   [lambdaisland.uri :as u]
   [lambdaisland.uri.normalize :as un]))

(d/export u/uri)
(d/export u/join)
(d/export u/query-encode)
(d/export un/percent-encode)
(d/export u/uri?)

(defn query-string->map
  [s]
  (u/query-string->map s))

(defn default-encode-value
  [v]
  (if (keyword? v) (name v) v))

(defn map->query-string
  ([params] (map->query-string params nil))
  ([params {:keys [value-fn key-fn]
            :or {value-fn default-encode-value
                 key-fn identity}}]
   (->> params
        (into {} (comp
                  (remove #(nil? (second %)))
                  (map (fn [[k v]] [(key-fn k) (value-fn v)]))))
        (u/map->query-string))))
