;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.registry
  "Shared registry logic for the color, unit, function and token schema
  managers.

  A registry is a map

    {:specs       {uri spec}
     :spec-types  {lower-case-name uri}
     :conversions {source-uri {target-uri conversion}}}

  Lookups resolve versions (`0.0.1` -> `0.0` -> `0` -> latest) and
  conversions between two schemas are found by a breadth first search
  over the registered conversion edges, so a chain of conversions can be
  used when no direct one exists.

  URI resolution and conversion paths are memoized per registry value:
  they run on every color or unit conversion but only depend on the
  registry."
  (:require
   [app.common.tokenscript.schema-uri :as suri]))

(def empty-registry
  {:specs {}
   :spec-types {}
   :conversions {}})

;; Memoization ----------------------------------------------------------------
;;
;; Registries are immutable and built once per config, so results are cached
;; per registry instance (by identity). Only a handful of registries are live
;; at a time (colors, units, functions, token types of the active config).

(def ^:private max-cached-registries 16)

(defonce ^:private registry-caches (atom []))

(defn- registry-cache
  [registry]
  (or (some (fn [[r cache]] (when (identical? r registry) cache)) @registry-caches)
      (let [cache (atom {})]
        (swap! registry-caches #(vec (take max-cached-registries (cons [registry cache] %))))
        cache)))

(defn- memoized
  "Value of `(f)` cached under `k` for `registry`."
  [registry k f]
  (let [cache (registry-cache registry)
        m @cache]
    (if (contains? m k)
      (get m k)
      (let [v (f)]
        (swap! cache assoc k v)
        v))))

(defn add-spec
  [registry uri spec name]
  (-> registry
      (assoc-in [:specs uri] spec)
      (assoc-in [:spec-types name] uri)))

(defn add-conversion
  [registry source-uri target-uri conversion]
  (assoc-in registry [:conversions source-uri target-uri] conversion))

(defn- latest-version-uri
  [registry uri]
  (when-let [source (suri/parse-uri uri)]
    (->> (keys (:specs registry))
         (keep (fn [spec-uri]
                 (let [target (suri/parse-uri spec-uri)]
                   (when (and target
                              (= (:base-url target) (:base-url source))
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
  (memoized registry [:resolve uri] #(resolve-uri* registry uri)))

(defn spec
  "Spec registered under `uri`, with version resolution."
  [registry uri]
  (or (get (:specs registry) uri)
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
  (memoized registry [:path source target] #(conversion-path* registry source target)))

(defn has-conversion?
  [registry source target]
  (let [conversions (:conversions registry)
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
  (memoized registry [:steps source target] #(conversion-steps* registry source target)))
