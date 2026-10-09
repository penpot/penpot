;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.config
  "The process configuration, read from the environment.

  Same shape as the legacy `app.config` it replaces: `PENPOT_`-prefixed
  variables become kebab-case keys over the defaults, decoded and
  validated against the schema. The one deliberate split: `prepare`
  over an explicit env object is pure and throws on invalid input (so
  tests exercise it), while only the wiring entry `load-config!` reads
  the live environment and exits on it — the process never boots with
  a config it cannot derive its management key from."
  (:refer-clojure :exclude [get])
  (:require
   ["node:buffer" :as buffer]
   ["node:crypto" :as crypto]
   ["node:process" :as process]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.flags :as flags]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.version :as v]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(l/set-level! :info)

(def ^:private defaults
  {:public-uri "http://localhost:3449"
   ;; :internal-uri nil ;; internal-uri cannot be nil
   :tenant "default"
   :tempdir "/tmp/penpot"
   :redis-uri "redis://redis/0"
   :exporter-job-ttl 3600
   ;; how many export jobs run at once: every poller owns one Redis
   ;; connection, and the pools size themselves to the same number
   :exporter-worker-concurrency 2
   :wasm-worker-pool-min 1
   :wasm-worker-idle-timeout 300
   :wasm-worker-image-cache-size (* 128 1024 1024)})

(def ^:private schema:config
  [:map {:title "config"}
   [:secret-key :string]
   [:public-uri {:optional true} ::sm/uri]
   [:internal-uri {:optional true} ::sm/uri]
   [:exporter-shared-key {:optional true} :string]
   [:tenant {:optional true} :string]
   [:flags {:optional true} [::sm/set :keyword]]
   [:redis-uri {:optional true} :string]
   [:tempdir {:optional true} :string]
   [:browser-pool-max {:optional true} ::sm/int]
   [:browser-pool-min {:optional true} ::sm/int]
   [:exporter-max-concurrent-jobs {:optional true} ::sm/int]
   [:exporter-max-jobs-per-profile {:optional true} ::sm/int]
   [:exporter-queue-max {:optional true} ::sm/int]
   [:exporter-job-ttl {:optional true} ::sm/int]
   [:exporter-roles {:optional true} :string]
   [:exporter-worker-concurrency {:optional true} ::sm/int]
   [:wasm-worker-pool-max {:optional true} ::sm/int]
   [:wasm-worker-pool-min {:optional true} ::sm/int]
   [:wasm-worker-idle-timeout {:optional true} ::sm/int]
   [:wasm-worker-image-cache-size {:optional true} ::sm/int]])

(def ^:private decode-config
  (sm/decoder schema:config sm/string-transformer))

(def ^:private explain-config
  (sm/explainer schema:config))

(def ^:private valid-config?
  (sm/validator schema:config))

(defn- parse-flags
  [config]
  (flags/parse (:flags config)))

(defn read-env
  "The `PENPOT_`-prefixed variables of `env` as kebab-case keys, the
  rest ignored. Takes the env object explicitly so tests pass a fake."
  [env]
  (let [prefix "penpot_"
        len    (count prefix)
        kwd    (fn [s] (-> (str/kebab s) (str/keyword)))]
    (reduce (fn [res key]
              (let [val (unchecked-get env key)
                    key (str/lower key)]
                (cond-> res
                  (str/starts-with? key prefix)
                  (assoc (kwd (subs key len)) val))))
            {}
            (js/Object.keys env))))

(defn prepare-config
  "Defaults plus `env`, decoded and validated. Throws on invalid input
  instead of exiting: only the wiring entry exits, on the live
  environment."
  [env]
  (let [data (-> (merge defaults (d/without-nils (read-env env)))
                 (decode-config))]
    (when-not (valid-config? data)
      (throw (ex/error :type :internal
                       :code :invalid-config
                       :hint (sm/humanize-explain (explain-config data)))))
    data))

(defn load-config!
  "The live environment as validated config. Exits the process on
  invalid input: without a secret there is no management key to derive,
  so there is nothing to boot."
  []
  (try
    (prepare-config (unchecked-get process "env"))
    (catch :default cause
      (println (ex-message cause))
      (process/exit -1))))

(def config
  (load-config!))

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

(defn get-internal-uri
  "Returns internal-uri if set, otherwise falls back to public-uri."
  []
  (or (c/get config :internal-uri)
      (c/get config :public-uri)))

(defn derive-management-key
  "The management key for `secret`: HKDF blake2b512 over \"exporter\",
  32 bytes, base64url. Pure so tests pin the formula the backend
  derives on its side."
  [secret]
  (-> (.from buffer/Buffer (crypto/hkdfSync "blake2b512" secret "exporter" "" 32))
      (.toString "base64url")))

(def management-key
  (let [key (or (c/get config :exporter-shared-key)
                (derive-management-key (c/get config :secret-key)))]
    (l/inf :hint "exporter key initialized" :key (d/obfuscate-string key))
    key))
