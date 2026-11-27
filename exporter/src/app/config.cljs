;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["node:buffer" :as buffer]
   ["node:crypto" :as crypto]
   ["node:fs" :as fs]
   ["node:os" :as os]
   ["node:process" :as process]
   [app.common.data :as d]
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
   :host "localhost"
   :http-server-port 6061
   :http-server-host "0.0.0.0"
   :tempdir "/tmp/penpot"
   :redis-uri "redis://redis/0"
   :exporter-max-concurrent-jobs 4
   :exporter-max-jobs-per-profile 2
   :exporter-queue-max 64
   :exporter-job-ttl 3600
   :wasm-worker-pool-max 2
   :wasm-worker-pool-min 1
   :wasm-worker-idle-timeout 300
   :wasm-worker-image-cache-size (* 128 1024 1024)})

(def ^:private schema:config
  [:map {:title "config"}
   [:secret-key {:optional true} :string]
   [:secret-key-file {:optional true} :string]
   [:public-uri {:optional true} ::sm/uri]
   [:internal-uri {:optional true} ::sm/uri]
   [:exporter-shared-key {:optional true} :string]
   [:exporter-shared-key-file {:optional true} :string]
   [:host {:optional true} :string]
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
   [:exporter-replica-id {:optional true} :string]
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

(defn- present?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- has-key-source?
  [config]
  (or (present? (c/get config :exporter-shared-key))
      (present? (c/get config :secret-key))))

(def ^:private max-key-file-size 4096)

(defn- get-key-file-value
  [variable path]
  (let [info (try
               (fs/statSync path)
               (catch :default cause
                 (l/warn :hint "unable to read configuration key file"
                         :variable variable
                         :path path
                         :cause cause)
                 nil))]
    (when (or (nil? info) (not (.isFile info)))
      (l/warn :hint "configuration key file is not a regular file"
              :variable variable
              :path path)
      (throw (js/Error. (str variable " key file is missing or empty: " path))))
    (when (> (.-size info) max-key-file-size)
      (throw (js/Error. (str variable " key file is too large: " path))))
    (let [value (try
                  (some-> (fs/readFileSync path "utf8")
                          (str/trim)
                          (str/replace #"\s+" ""))
                  (catch :default cause
                    (l/warn :hint "unable to read configuration key file"
                            :variable variable
                            :path path
                            :cause cause)
                    nil))]
      (when (str/blank? value)
        (throw (js/Error. (str variable " key file is missing or empty: " path))))
      value)))

(defn prepare-config
  ([] (prepare-config (read-env "penpot")))
  ([env]
   (let [env  (d/without-nils env)
         _    (when (and (contains? env :exporter-shared-key)
                         (not (present? (c/get env :exporter-shared-key))))
                (throw (js/Error. "Either PENPOT_EXPORTER_SHARED_KEY or PENPOT_SECRET_KEY is required; blank PENPOT_EXPORTER_SHARED_KEY disables the exporter")))
         env  (if (and (present? (c/get env :exporter-shared-key-file))
                       (not (contains? env :exporter-shared-key)))
                (assoc env :exporter-shared-key
                       (get-key-file-value "PENPOT_EXPORTER_SHARED_KEY_FILE"
                                           (c/get env :exporter-shared-key-file)))
                env)
         env  (if (and (not (contains? env :exporter-shared-key))
                       (not (contains? env :secret-key))
                       (present? (c/get env :secret-key-file)))
                (assoc env :secret-key
                       (get-key-file-value "PENPOT_SECRET_KEY_FILE"
                                           (c/get env :secret-key-file)))
                env)
         data (merge defaults env)
         data (decode-config data)]

     (when-not (valid-config? data)
       (throw (js/Error. (sm/humanize-explain (explain-config data)))))

     (when-not (has-key-source? data)
       (throw (js/Error. "Either PENPOT_EXPORTER_SHARED_KEY or PENPOT_SECRET_KEY is required")))

     data)))

(def config
  (try
    (prepare-config)
    (catch :default e
      (println (.-message e))
      (process/exit -1))))

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

(def replica-id
  "Names this exporter among its siblings. It must survive a restart, since
  the boot sweep uses it to find the jobs the previous run left behind."
  (or (c/get config :exporter-replica-id)
      (os/hostname)))

(defn get-management-key
  [config]
  (let [explicit   (c/get config :exporter-shared-key)
        secret-key (c/get config :secret-key)]
    (cond
      (present? explicit) explicit
      (contains? config :exporter-shared-key)
      (throw (js/Error. "Blank PENPOT_EXPORTER_SHARED_KEY disables the exporter"))
      (present? secret-key)
      (let [derived-key (crypto/hkdfSync "blake2b512" secret-key "exporter" "" 32)]
        (-> (.from buffer/Buffer derived-key)
            (.toString "base64url")))
      :else
      (throw (js/Error. "Either PENPOT_EXPORTER_SHARED_KEY or PENPOT_SECRET_KEY is required")))))

(def management-key
  (let [key (get-management-key config)]
    (l/inf :hint "exporter key initialized" :key (d/obfuscate-string key))
    key))
