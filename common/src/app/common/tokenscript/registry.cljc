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
    spec-types   {lower-case-name uri}
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
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.schema-uri :as suri]))

(defprotocol ISchemaRegistry
  "A registry of one kind of schema: color spaces, units, functions or
  token types."
  (register [_ uri spec]
    "Registry with `spec` registered under `uri`. Raises
    `:config/invalid-specification` when the spec is malformed."))

;; Memoization ----------------------------------------------------------------
;;
;; The `cache` field of a registry holds the results of `resolve-uri`,
;; `conversion-path` and `conversion-steps`. `add-spec` and
;; `add-conversion` give the new registry a new cache, so a registry never
;; sees results computed for the one it was built from.

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
  [registry uri spec name]
  (let [uri (suri/normalize uri)]
    (-> registry
        (assoc-in [:specs uri] spec)
        (assoc-in [:spec-types name] uri)
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

(defn spec-by-name
  [registry name]
  (when-let [uri (get (:spec-types registry) name)]
    (spec registry uri)))

(defn- resolved-or-self
  [registry uri]
  (or (resolve-uri registry uri) uri))

(defn- conversion-path*
  [registry source target]
  (if (suri/same-schema? source target)
    [source]
    (let [conversions (:conversions registry)
          sources-for (fn [current]
                        (distinct
                         (concat [current]
                                 (let [r (resolve-uri registry current)] (when (and r (not= r current)) [r]))
                                 (keep (fn [registered]
                                         (when (or (= registered current)
                                                   (= (resolve-uri registry registered) current))
                                           registered))
                                       (keys conversions)))))]
      (loop [queue [[source [source]]]
             visited #{(suri/base-uri source)}]
        (if (empty? queue)
          []
          (let [[current path] (first queue)
                queue (vec (rest queue))
                edges (for [s (sources-for current)
                            uri (keys (get conversions s))]
                        uri)
                hit (some #(when (suri/same-schema? % target) %) edges)]
            (if hit
              (conj path hit)
              (let [[queue visited]
                    (reduce (fn [[q v] uri]
                              (let [b (suri/base-uri uri)]
                                (if (contains? v b)
                                  [q v]
                                  [(conj q [uri (conj path uri)]) (conj v b)])))
                            [queue visited]
                            edges)]
                (recur queue visited)))))))))

(defn conversion-path
  "Sequence of URIs from `source` to `target` (inclusive) found by BFS,
  or empty when unreachable."
  [registry source target]
  (let [source (suri/normalize source)
        target (suri/normalize target)]
    (memoized registry [:path source target] #(conversion-path* registry source target))))

(defn has-conversion?
  [registry source target]
  (let [source (suri/normalize source)
        target (suri/normalize target)
        conversions (:conversions registry)
        rs (resolved-or-self registry source)
        rt (resolved-or-self registry target)]
    (or (contains? (get conversions source) target)
        (contains? (get conversions rs) rt)
        (some (fn [registered-target]
                (= (resolved-or-self registry registered-target) rt))
              (keys (get conversions rs)))
        (seq (conversion-path registry rs rt)))))

(defn- find-step
  "Conversion registered between `from` and `to`, trying resolved URIs."
  [registry from to]
  (let [conversions (:conversions registry)
        rf (resolved-or-self registry from)
        rt (resolved-or-self registry to)]
    (or (get-in conversions [from to])
        (get-in conversions [rf rt])
        (some (fn [[registered-to conv]]
                (when (= (resolved-or-self registry registered-to) rt) conv))
              (get conversions rf))
        (some (fn [[registered-from targets]]
                (when (= (resolved-or-self registry registered-from) rf)
                  (some (fn [[registered-to conv]]
                          (when (= (resolved-or-self registry registered-to) rt) conv))
                        targets)))
              conversions))))

(defn- conversion-steps*
  [registry source target]
  (let [rs (resolved-or-self registry source)
        rt (resolved-or-self registry target)]
    (cond
      (= rs rt) []

      (get-in (:conversions registry) [rs rt])
      [(get-in (:conversions registry) [rs rt])]

      :else
      (let [path (conversion-path registry rs rt)]
        (when (seq path)
          (let [steps (map (fn [[from to]] (find-step registry from to)) (partition 2 1 path))]
            (when (every? some? steps)
              (vec steps))))))))

(defn conversion-steps
  "Conversions to apply, in order, to go from `source` to `target`.
  Returns `[]` for an identity conversion and nil when no path exists or
  a step is missing."
  [registry source target]
  (let [source (suri/normalize source)
        target (suri/normalize target)]
    (memoized registry [:steps source target] #(conversion-steps* registry source target))))
