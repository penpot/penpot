;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["process" :as process]
   [app.common.data :as d]
   [app.common.flags :as flags]
   [app.common.schema :as sm]
   [app.common.version :as v]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(def ^:private defaults
  {:public-uri "http://localhost:3449"
   :tenant "dev"
   :host "localhost"
   :http-server-port 6061
   :http-server-host "0.0.0.0"
   :tempdir "/tmp/penpot-exporter"
   :redis-uri "redis://redis/0"})

(def ^:private
  schema:config
  (sm/define
    [:map {:title "config"}
     [:public-uri {:optional true} ::sm/uri]
     [:host {:optional true} :string]
     [:tenant {:optional true} :string]
     [:flags {:optional true} ::sm/set-of-keywords]
     [:redis-uri {:optional true} :string]
     [:tempdir {:optional true} :string]
     [:browser-pool-max {:optional true} :int]
     [:browser-pool-min {:optional true} :int]]))

(defn- parse-flags
  [config]
  (flags/parse (:flags config)))

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

    (try
      (sm/conform! schema:config data)
      (catch :default cause
        (if-let [explain (some->> cause ex-data ::sm/explain)]
          (println (sm/humanize-explain explain))
          (js/console.error cause))
        (process/exit -1)))))

(def config
  (prepare-config))

(def version
  (v/parse "%version%"))

(def flags
  (parse-flags config))

(defn get
  "A configuration getter."
  ([key]
   (c/get config key))
  ([key default]
   (c/get config key default)))
