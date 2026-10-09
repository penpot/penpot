;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.schema-uri
  "Parsing and construction of tokenscript schema URIs.

  Schemas are identified by URIs of the form

    https://host/api/v1/<category>/<name>/<version>/

  where category is `schema`, `core` or `function` and version is a
  semantic version prefix (`0`, `0.1`, `0.1.2`) or `latest`. Versions are
  maps `{:major 0 :minor 1 :patch 2}` with minor/patch optional, or the
  keyword `:latest`."
  (:require
   [clojure.string :as str]))

(def default-registry-url "https://schema.tokenscript.dev.gcp.tokens.studio")
(def default-api-path "/api/v1")

(defn- parse-int
  [s]
  (when (re-matches #"\d+" s)
    #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))))

(defn parse-semver
  "`\"0.1.2\"` -> `{:major 0 :minor 1 :patch 2}`, nil when not numeric."
  [s]
  (let [parts (str/split s #"\.")
        nums (map parse-int parts)]
    (when (and (every? some? nums) (<= 1 (count nums) 3))
      (let [[major minor patch] nums]
        (cond-> {:major major}
          (some? minor) (assoc :minor minor)
          (some? patch) (assoc :patch patch))))))

(defn parse-version
  [s]
  (if (= s "latest") :latest (parse-semver s)))

(defn version->string
  [version]
  (cond
    (map? version)
    (let [{:keys [major minor patch]} version]
      (cond
        (some? patch) (str major "." minor "." patch)
        (some? minor) (str major "." minor)
        :else (str major)))
    :else "latest"))

(defn build-uri
  "Builds a schema URI from its components. A nil `:base-url` uses the
  default registry, an empty one builds a path only URI."
  [{:keys [base-url category name version]}]
  (let [base (if (nil? base-url) default-registry-url base-url)]
    (str base default-api-path "/" category "/" name "/" (version->string version) "/")))

(defn- split-url
  "Returns `[base-url pathname]` for a URI; nil for an unparseable
  absolute URL."
  [uri]
  (if-let [[_ scheme-host path] (re-matches #"^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]*)(/.*)?$" uri)]
    [scheme-host (or path "/")]
    (if (str/includes? uri "://")
      nil
      ["" uri])))

(defn- parse-uri*
  [uri]
  (when-let [[base-url pathname] (split-url uri)]
    (let [parts (remove str/blank? (str/split pathname #"/"))
          [api v category name version] parts]
      (when (and (>= (count parts) 5)
                 (= api "api")
                 (str/starts-with? (str v) "v")
                 (contains? #{"schema" "core" "function"} category))
        {:base-url base-url
         :category category
         :name name
         :version (parse-version version)}))))

;; Schema URIs come from a small set (the registered schemas and their
;; version candidates) and are parsed on every lookup, so parses are cached.
(def ^:private max-cached-uris 4096)

(defonce ^:private uri-cache (atom {}))

(defn parse-uri
  "Parses a schema URI into
  `{:base-url .. :category .. :name .. :version ..}` or nil."
  [uri]
  (let [m @uri-cache]
    (if (contains? m uri)
      (get m uri)
      (let [v (parse-uri* uri)]
        (swap! uri-cache (fn [m] (assoc (if (>= (count m) max-cached-uris) {} m) uri v)))
        v))))

(defn base-uri
  "URI without the version segment."
  [uri]
  (if-let [{:keys [base-url category name]} (parse-uri uri)]
    (str base-url default-api-path "/" category "/" name "/")
    uri))

(defn semver
  "Semantic version of a URI or nil for `latest`/unparseable."
  [uri]
  (let [v (:version (parse-uri uri))]
    (when (map? v) v)))

(defn version-candidates
  "URIs to try when resolving `uri`, from most to least specific:
  `0.0.1` -> `[0.0.1 0.0 0 latest]`."
  [uri]
  (if-let [{:keys [version] :as components} (parse-uri uri)]
    (if (map? version)
      (let [{:keys [major minor patch]} version]
        (cond-> [uri]
          (some? patch) (conj (build-uri (assoc components :version {:major major :minor minor})))
          (some? minor) (conj (build-uri (assoc components :version {:major major})))
          :always (conj (build-uri (assoc components :version :latest)))))
      [uri])
    [uri]))

(defn compare-versions
  [a b]
  (let [c (compare (:major a) (:major b))]
    (if (not= 0 c)
      c
      (let [c (compare (or (:minor a) 0) (or (:minor b) 0))]
        (if (not= 0 c)
          c
          (compare (or (:patch a) 0) (or (:patch b) 0)))))))

(defn same-schema?
  "Whether two URIs identify the same schema ignoring the version."
  [a b]
  (let [ca (parse-uri a) cb (parse-uri b)]
    (if (and ca cb)
      (and (= (:base-url ca) (:base-url cb))
           (= (:category ca) (:category cb))
           (= (:name ca) (:name cb)))
      (= (base-uri a) (base-uri b)))))

(defn schema-name
  [uri]
  (:name (parse-uri uri)))
