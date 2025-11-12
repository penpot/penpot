;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.uri
  (:refer-clojure :exclude [uri?])
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [lambdaisland.uri.normalize :as un])
  #?(:clj
     (:import lambdaisland.uri.URI)))

(dm/export u/join)
(dm/export u/parse)
(dm/export u/query-encode)
(dm/export un/percent-encode)
(dm/export u/uri?)

(defn uri
  [o]
  (cond
    (u/uri? o)
    o

    (map? o)
    (u/map->URI o)

    (nil? o)
    o

    :else
    (u/parse o)))

(defn query-string->map
  [s]
  (u/query-string->map s))

(defn default-encode-value
  [v]
  (if (keyword? v) (name v) v))

(defn get-domain
  [{:keys [host port] :as uri}]
  (cond-> host
    port (str ":" port)))

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

(defn ensure-path-slash
  [u]
  (update (uri u) :path
          (fn [path]
            (if (str/ends-with? path "/")
              path
              (str path "/")))))

#?(:clj
   (defmethod print-method lambdaisland.uri.URI [^URI this ^java.io.Writer writer]
     (.write writer "#")
     (.write writer (str u/edn-tag))
     (.write writer " ")
     (.write writer (pr-str (.toString this))))

   :cljs
   (extend-type u/URI
     IPrintWithWriter
     (-pr-writer [this writer _opts]
       (write-all writer "#" (str u/edn-tag) " " (pr-str (.toString this))))))
