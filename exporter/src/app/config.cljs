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
   [cljs.core :as c]
   [cljs.pprint]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]))

(def defaults
  {:public-uri "http://localhost:3449"
   :tenant "dev"
   :host "devenv"
   :http-server-port 6061
   :browser-concurrency 5
   :browser-strategy :incognito})

(s/def ::browser-concurrency ::us/integer)
(s/def ::browser-executable-path ::us/string)
(s/def ::browser-strategy ::us/keyword)
(s/def ::http-server-port ::us/integer)
(s/def ::public-uri ::us/string)
(s/def ::sentry-dsn ::us/string)
(s/def ::tenant ::us/string)
(s/def ::host ::us/string)

(s/def ::config
  (s/keys :opt-un [::public-uri
                   ::sentry-dsn
                   ::host
                   ::tenant
                   ::http-server-port
                   ::browser-concurrency
                   ::browser-strategy
                   ::browser-executable-path]))
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
  (let [env  (read-env "penpot")
        env  (d/without-nils env)
        data (merge defaults env)]
    (us/conform ::config data)))

(def config
  (atom (prepare-config)))

(def version
  (atom (v/parse (or (some-> (ex/ignoring (fs/readFileSync "version.txt"))
                             (str/trim))
                     "%version%"))))

(defn get
  "A configuration getter."
  ([key]
   (c/get @config key))
  ([key default]
   (c/get @config key default)))
