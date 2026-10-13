;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.registry
  "Shared registry logic for the color, unit, function and token schema
  managers.

  Each manager has its own registry record implementing
  `ISchemaRegistry`. The functions here work on the fields the records
  share:

    specs        {uri spec}
    spec-types   {name-key uri}, see `app.common.tokenscript.util/name-key`
    conversions  {source-uri {target-uri conversion}}
    cache        memoized lookups, see `new-cache`

  URIs are stored and looked up in their path form (see
  `app.common.tokenscript.schema-uri/normalize`), so a schema referenced
  with and without a host is the same schema.

  Lookups resolve versions (`0.0.1` -> `0.0` -> `0` -> latest) and
  conversions between two schemas are found by a breadth first search
  over the registered conversion edges, so a chain of conversions can be
  used when no direct one exists.

  URI resolution and conversion paths are memoized per registry: they
  run on every color or unit conversion but only depend on the specs and
  conversions."
  (:require
   [app.common.data :as d]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.schema-uri :as suri]
   [app.common.tokenscript.util :as u]))

(defprotocol ISchemaRegistry
  "A registry of one kind of schema: color spaces, units, functions or
  token types."
  (register [_ uri spec]
    "Registry with `spec` registered under `uri`. Raises
    `:config/invalid-specification` when the spec is malformed."))

;; Memoization ----------------------------------------------------------------
;;
;; The `cache` field of a registry holds the results of `resolve-uri` and
;; of the conversion search. `add-spec` and `add-conversion` give the new
;; registry a new cache, so a registry never sees results computed for
;; the one it was built from.

(defn new-cache
  []
  (atom {}))

(defn- memoized
  "Value of `(f)` cached under `k` for `registry`."
  [registry k f]
  (let [cache (:cache registry)
        m @cache]
    (if (contains? m k)
      (get m k)
      (let [v (f)]
        (swap! cache assoc k v)
        v))))

(defn add-spec
  "Registry with `spec` under `uri`, also found by its case insensitive
  `:name`."
  [registry uri spec]
  (let [uri (suri/normalize uri)]
    (-> registry
        (assoc-in [:specs uri] spec)
        (assoc-in [:spec-types (u/name-key (:name spec))] uri)
        (assoc :cache (new-cache)))))

(defn add-conversion
  [registry source-uri target-uri conversion]
  (-> registry
      (assoc-in [:conversions (suri/normalize source-uri) (suri/normalize target-uri)] conversion)
      (assoc :cache (new-cache))))

(defn add-conversions
  "Parses and adds the conversion scripts of the spec registered under
  `uri`. `$self` in a source or target stands for `uri`."
  [registry uri conversions]
  (reduce (fn [registry {:keys [source target script]}]
            (let [source-uri (suri/normalize (if (= source "$self") uri source))
                  target-uri (suri/normalize (if (= target "$self") uri target))
                  ast (:ast (parser/parse (:script script)))]
              (add-conversion registry source-uri target-uri
                              {:ast ast :source-uri source-uri :target-uri target-uri})))
          registry
          conversions))

(defn- latest-version-uri
  [registry uri]
  (when-let [source (suri/parse-uri uri)]
    (->> (keys (:specs registry))
         (keep (fn [spec-uri]
                 (let [target (suri/parse-uri spec-uri)]
                   (when (and target
                              (= (:category target) (:category source))
                              (= (:name target) (:name source))
                              (map? (:version target)))
                     [spec-uri (:version target)]))))
         (sort (fn [[_ a] [_ b]] (suri/compare-versions b a)))
         (ffirst))))

(defn- resolve-uri*
  [registry uri]
  (if (re-find #"/latest/" uri)
    (latest-version-uri registry uri)
    (loop [candidates (suri/version-candidates uri)]
      (when-let [candidate (first candidates)]
        (cond
          (contains? (:specs registry) candidate) candidate
          (re-find #"/latest/" candidate)
          (or (latest-version-uri registry candidate) (recur (rest candidates)))
          :else (recur (rest candidates)))))))

(defn resolve-uri
  "Registered URI matching `uri` after version resolution, or nil."
  [registry uri]
  (let [uri (suri/normalize uri)]
    (memoized registry [:resolve uri] #(resolve-uri* registry uri))))

(defn spec
  "Spec registered under `uri`, with version resolution."
  [registry uri]
  (or (get (:specs registry) (suri/normalize uri))
      (when-let [resolved (resolve-uri registry uri)]
        (get (:specs registry) resolved))))

(defn uri-by-name
  "URI of the spec named `name` (case insensitive)."
  [registry name]
  (u/get-name (:spec-types registry) name))

(defn spec-by-name
  "Spec named `name` (case insensitive)."
  [registry name]
  (when-let [uri (uri-by-name registry name)]
    (spec registry uri)))

(defn- resolved-or-self
  [registry uri]
  (or (resolve-uri registry uri) uri))

(defn- sources-for
  "Registered conversion sources standing for `uri`: the URI, the
  registered URI it resolves to and the registered URIs resolving to it."
  [registry uri]
  (let [resolved (resolve-uri registry uri)]
    (distinct
     (concat [uri]
             (when (and resolved (not= resolved uri)) [resolved])
             (filter #(or (= % uri) (= (resolve-uri registry %) uri))
                     (keys (:conversions registry)))))))

(defn- search*
  [registry source target]
  (if (suri/same-schema? source target)
    {:path [source] :steps []}
    (let [conversions (:conversions registry)
          hit (fn [[to :as edge]] (when (suri/same-schema? to target) edge))]
      (loop [queue (d/queue {:uri source :path [source] :steps []})
             visited #{(suri/base-uri source)}]
        (when-let [{:keys [uri path steps]} (peek queue)]
          (let [edges (for [s (sources-for registry uri)
                            edge (get conversions s)]
                        edge)]
            (if-let [[to conversion] (some hit edges)]
              {:path (conj path to) :steps (conj steps conversion)}
              (let [[queue visited]
                    (reduce (fn [[queue visited :as acc] [to conversion]]
                              (let [base (suri/base-uri to)]
                                (if (contains? visited base)
                                  acc
                                  [(conj queue {:uri to :path (conj path to) :steps (conj steps conversion)})
                                   (conj visited base)])))
                            [(pop queue) visited]
                            edges)]
                (recur queue visited)))))))))

(defn- search
  "Shortest chain of conversions from `source` to `target`, found by a
  breadth first search over the conversion edges: `{:path [uri ..]
  :steps [conversion ..]}`, nil when unreachable."
  [registry source target]
  (memoized registry [:search source target] #(search* registry source target)))

(defn conversion-path
  "Sequence of URIs from `source` to `target` (inclusive), or empty when
  unreachable."
  [registry source target]
  (or (:path (search registry (suri/normalize source) (suri/normalize target))) []))

(defn has-conversion?
  [registry source target]
  (boolean (seq (conversion-path registry
                                 (resolved-or-self registry (suri/normalize source))
                                 (resolved-or-self registry (suri/normalize target))))))

(defn- conversion-steps*
  [registry source target]
  (let [source (resolved-or-self registry source)
        target (resolved-or-self registry target)
        direct (get-in (:conversions registry) [source target])]
    (cond
      (= source target) []
      (some? direct) [direct]
      :else (:steps (search registry source target)))))

(defn conversion-steps
  "Conversions to apply, in order, to go from `source` to `target`.
  Returns `[]` for an identity conversion and nil when no path exists.
  Runs on every conversion, so the result is memoized."
  [registry source target]
  (let [source (suri/normalize source)
        target (suri/normalize target)]
    (memoized registry [:steps source target] #(conversion-steps* registry source target))))
