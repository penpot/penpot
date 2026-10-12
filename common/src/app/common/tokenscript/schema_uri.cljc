;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.schema-uri
  "Parsing and construction of tokenscript schema URIs.

  Schemas are identified by path URIs of the form

    /api/v1/<category>/<name>/<version>/

  where category is `schema`, `core` or `function` and version is a
  semantic version prefix (`0`, `0.1`, `0.1.2`) or `latest`. Versions are
  maps `{:major 0 :minor 1 :patch 2}` with minor/patch optional, or the
  keyword `:latest`.

  Schemas are never fetched, so the host of an absolute URI
  (`https://host/api/v1/...`, as written by other tokenscript tools) is
  not part of the identity: `normalize` drops it and the registries only
  hold path URIs."
  (:require
   [clojure.string :as str]))

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
  "Builds a schema URI from its components."
  [{:keys [category name version]}]
  (str default-api-path "/" category "/" name "/" (version->string version) "/"))

(defn- normalize*
  [uri]
  (if-let [[_ path] (re-matches #"^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*(/.*)?$" uri)]
    (or path "/")
    uri))

;; Schema URIs come from a small set (the registered schemas and their
;; version candidates) and are normalized and parsed on every lookup, so
;; both are cached.
(def ^:private max-cached-uris 4096)

(defonce ^:private normalize-cache (atom {}))

(defn normalize
  "Path URI of a schema URI: `https://host/api/v1/core/x/0/` ->
  `/api/v1/core/x/0/`. Other values (path URIs, `$self`, nil) are
  returned as is."
  [uri]
  (if-not (string? uri)
    uri
    (let [m @normalize-cache]
      (if-let [v (get m uri)]
        v
        (let [v (normalize* uri)]
          (swap! normalize-cache (fn [m] (assoc (if (>= (count m) max-cached-uris) {} m) uri v)))
          v)))))

(defn- parse-uri*
  [uri]
  (let [parts (remove str/blank? (str/split (normalize uri) #"/"))
        [api v category name version] parts]
    (when (and (>= (count parts) 5)
               (= api "api")
               (str/starts-with? (str v) "v")
               (contains? #{"schema" "core" "function"} category))
      {:category category
       :name name
       :version (parse-version version)})))

(defonce ^:private uri-cache (atom {}))

(defn parse-uri
  "Parses a schema URI into `{:category .. :name .. :version ..}` or
  nil. The host of absolute URIs is ignored."
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
  (if-let [{:keys [category name]} (parse-uri uri)]
    (str default-api-path "/" category "/" name "/")
    (normalize uri)))

(defn semver
  "Semantic version of a URI or nil for `latest`/unparseable."
  [uri]
  (let [v (:version (parse-uri uri))]
    (when (map? v) v)))

(defn version-candidates
  "URIs to try when resolving `uri`, from most to least specific:
  `0.0.1` -> `[0.0.1 0.0 0 latest]`."
  [uri]
  (let [uri (normalize uri)]
    (if-let [{:keys [version] :as components} (parse-uri uri)]
      (if (map? version)
        (let [{:keys [major minor patch]} version]
          (cond-> [uri]
            (some? patch) (conj (build-uri (assoc components :version {:major major :minor minor})))
            (some? minor) (conj (build-uri (assoc components :version {:major major})))
            :always (conj (build-uri (assoc components :version :latest)))))
        [uri])
      [uri])))

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
      (and (= (:category ca) (:category cb))
           (= (:name ca) (:name cb)))
      (= (base-uri a) (base-uri b)))))

(defn schema-name
  [uri]
  (:name (parse-uri uri)))
