;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["process" :as process]
   [cljs.pprint]
   [cuerdas.core :as str]
   [app.common.spec :as us]
   [cljs.spec.alpha :as s]
   [cljs.core :as c]
   [lambdaisland.uri :as u]))

(def defaults
  {:public-uri "http://localhost:3449"
   :http-server-port 6061
   :browser-concurrency 5
   :browser-strategy :incognito})

(s/def ::public-uri ::us/string)
(s/def ::http-server-port ::us/integer)
(s/def ::browser-concurrency ::us/integer)
(s/def ::browser-strategy ::us/keyword)

(s/def ::config
  (s/keys :opt-un [::public-uri
                   ::http-server-port
                   ::browser-concurrency
                   ::browser-strategy]))
(defn- read-env
  [prefix]
  (let [env    (unchecked-get process "env")
        kwd    (fn [s] (-> (str/kebab s) (str/keyword)))
        prefix (str prefix "-")
        len    (count prefix)]
    (reduce (fn [res key]
              (cond-> res
                (str/starts-with? key prefix)
                (assoc (kwd (subs key len))
                       (unchecked-get env key))))
            {}
            (js/Object.keys env))))

(def config
  (atom (->> (read-env "penpot")
             (merge defaults)
             (us/conform ::config))))

(defn get
  "A configuration getter."
  ([key]
   (c/get @config key))
  ([key default]
   (c/get @config key default)))
