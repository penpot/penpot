;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["node:buffer" :as buffer]
   ["node:crypto" :as crypto]
   ["node:process" :as process]
   [app.common.data :as d]
   [app.common.flags :as flags]
   [app.common.schema :as sm]
   [app.common.version :as v]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(def ^:private defaults
  {:public-uri "http://localhost:3449"
   :tenant "default"
   :host "localhost"
   :http-server-port 6061
   :http-server-host "0.0.0.0"
   :tempdir "/tmp/penpot"
   :redis-uri "redis://redis/0"})

(def ^:private schema:config
  [:map {:title "config"}
   [:secret-key :string]
   [:public-uri {:optional true} ::sm/uri]
   [:management-api-key {:optional true} :string]
   [:host {:optional true} :string]
   [:tenant {:optional true} :string]
   [:flags {:optional true} [::sm/set :keyword]]
   [:redis-uri {:optional true} :string]
   [:tempdir {:optional true} :string]
   [:browser-pool-max {:optional true} ::sm/int]
   [:browser-pool-min {:optional true} ::sm/int]])

(def ^:private decode-config
  (sm/decoder schema:config sm/string-transformer))

(def ^:private explain-config
  (sm/explainer schema:config))

(def ^:private valid-config?
  (sm/validator schema:config))

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
        data (merge defaults env)
        data (decode-config data)]

    (when-not (valid-config? data)
      (let [explain (explain-config data)]
        (println (sm/humanize-explain explain))
        (process/exit -1)))

    data))

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

(def management-key
  (or (c/get config :management-api-key)
      (let [secret-key  (c/get config :secret-key)
            derived-key (crypto/hkdfSync "blake2b512" secret-key, "management" "" 32)]
        (-> (.from buffer/Buffer derived-key)
            (.toString "base64url")))))
