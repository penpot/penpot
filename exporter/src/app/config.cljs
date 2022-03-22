;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["fs" :as fs]
   ["process" :as process]
   [app.common.exceptions :as ex]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.version :as v]
   [app.common.uri :as u]
   [cljs.core :as c]
   [cljs.pprint]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]))

(def defaults
  {:public-uri "http://localhost:3449"
   :tenant "dev"
   :host "devenv"
   :http-server-port 6061
   :http-server-host "localhost"
   :redis-uri "redis://redis/0"
   :exporter-domain-whitelist #{"localhost2:3449"}})

(s/def ::http-server-port ::us/integer)
(s/def ::http-server-host ::us/string)
(s/def ::public-uri ::us/uri)
(s/def ::tenant ::us/string)
(s/def ::host ::us/string)

(s/def ::exporter-domain-whitelist ::us/set-of-str)
(s/def ::exporter-browser-pool-max ::us/integer)
(s/def ::exporter-browser-pool-min ::us/integer)

(s/def ::config
  (s/keys :opt-un [::public-uri
                   ::host
                   ::tenant
                   ::http-server-port
                   ::http-server-host
                   ::exporter-browser-pool-max
                   ::exporter-browser-pool-min
                   ::exporter-domain-whitelist]))

(defn- read-env
  [prefix]
  (let [env    (unchecked-get process "env")
        kwd    (fn [s] (-> (str/kebab s) (str/keyword)))
        prefix (str prefix "_")
        len    (count prefix)]
    (reduce (fn [res key]
              (let [val (unchecked-get env key)
                    key (str/lower key)]
                (cond-> res
                  (str/starts-with? key prefix)
                  (assoc (kwd (subs key len)) val))))
            {}
            (js/Object.keys env))))

(defn- prepare-config
  []
  (try
    (let [env  (read-env "penpot")
          env  (d/without-nils env)
          data (merge defaults env)]
      (us/conform ::config data))
    (catch :default cause
      (js/console.log (us/pretty-explain (ex-data cause)))
      (throw cause))))

(def config
  (atom (prepare-config)))

(def version
  (atom (v/parse "%version%")))

(defn get
  "A configuration getter."
  ([key]
   (c/get @config key))
  ([key default]
   (c/get @config key default)))
