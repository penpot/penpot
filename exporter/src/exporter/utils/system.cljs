;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.utils.system
  "Async-aware Integrant-style system lifecycle for the new tree.

  The shape mirrors `integrant.core`, trimmed down: a config map of
  qualified keys to values, `ref`s between them, and two multimethods,
  `init-key` and `halt-key`, that turn values into running components
  and back. The dependency graph rides `weavejester.dependency`, the
  same library Integrant uses.

  The difference is async: `init` and `halt` are `^:async`, every step
  is awaited, and an `init-key` method may return a plain value or a
  promise. `init` never throws synchronously; every failure, including
  invalid config, surfaces as a rejected promise.

  Deliberately left out from Integrant: refsets, profiles, vars,
  expand/converge, suspend/resume, composite keys, EDN reading and
  namespace loading."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [weavejester.dependency :as dep]))

(defrecord Ref [key])

(defn ref
  "Create a reference to a top-level key in a config map. The ref
  resolves to the running instance, once its target has started."
  [key]
  (when-not (qualified-keyword? key)
    (throw (ex-info (str "Invalid reference: " key ". Must be a qualified keyword.")
                    {:reason ::invalid-ref
                     :ref    key})))
  (->Ref key))

(defn ref?
  "Return true if its argument is a ref."
  [x]
  (instance? Ref x))

(defn- derived-from?
  [key candidate]
  (or (= key candidate) (isa? key candidate)))

(defn find-derived
  "Return a seq of all entries in a map, m, where the key is the
  candidate key or derived from it. If there are no matching keys, nil
  is returned."
  [m k]
  (seq (filter #(or (= (key %) k) (derived-from? (key %) k)) m)))

(defn- find-derived-1
  [m k]
  (let [kvs (find-derived m k)]
    (when (next kvs)
      (throw (ex-info (str "Ambiguous key: " k ". Found multiple candidates: "
                           (str/join ", " (map key kvs)))
                      {:reason   ::ambiguous-key
                       :key      k
                       :matching (mapv key kvs)})))
    (first kvs)))

(defn- depth-search
  [pred? coll]
  (filter pred? (tree-seq coll? seq coll)))

(defn- find-derived-refs
  [config v]
  (->> (depth-search ref? v)
       (map :key)
       (mapcat #(map key (find-derived config %)))))

(defn- dependency-graph
  [config]
  (reduce-kv (fn [g k v]
               (reduce #(dep/depend %1 k %2) g (find-derived-refs config v)))
             (dep/graph)
             config))

(defn- key-comparator
  [graph]
  (dep/topo-comparator #(compare (str %1) (str %2)) graph))

(defn- config-keyset
  [config ks]
  (set (mapcat #(map key (find-derived config %)) ks)))

(defn- validate-init
  "Check the config and the requested keys, and return the relevant
  keys in init order: requested, plus their transitive dependencies.
  A circular dependency surfaces the `weavejester.dependency` ex-info."
  [config ks]
  (doseq [k (keys config)]
    (when-not (qualified-keyword? k)
      (throw (ex-info (str "Invalid config key: " k ". Keys must be qualified keywords.")
                      {:reason ::invalid-config
                       :key    k}))))
  (doseq [k ks]
    (when-not (seq (find-derived config k))
      (throw (ex-info (str "Unknown key: " k ". It matches nothing in the config.")
                      {:reason ::unknown-key
                       :key    k}))))
  (let [graph    (dependency-graph config)
        keyset   (config-keyset config ks)
        relevant (select-keys config (set/union keyset
                                                (dep/transitive-dependencies-set graph keyset)))
        ref-keys (distinct (map :key (depth-search ref? (vals relevant))))
        missing  (filterv #(nil? (find-derived relevant %)) ref-keys)]
    (when (seq missing)
      (throw (ex-info (str "Missing definitions for refs: " (str/join ", " missing))
                      {:reason      ::missing-refs
                       :config      config
                       :missing-refs missing})))
    (doseq [k ref-keys]
      (find-derived-1 relevant k))
    (let [comparator (key-comparator graph)]
      (vec (sort comparator (keys relevant))))))

(defn- resolve-refs
  [sys v]
  (walk/postwalk #(if (ref? %) (val (find-derived-1 sys (:key %))) %) v))

(defmulti init-key
  "Turn a config value associated with a key into a running component.
  May return a plain value or a promise; the system awaits it either way."
  {:arglists '([key config])}
  (fn [key _config] key))

(defmethod init-key :default
  [k _config]
  (throw (ex-info (str "No init-key method for " k)
                  {:reason ::missing-init-key
                   :key    k})))

(defmulti halt-key
  "Stop the running component associated with a key. Receives the
  running instance returned by `init-key`, so anything halt needs must
  travel inside it. May return a plain value or a promise; the system
  awaits it either way. Must be idempotent."
  {:arglists '([key instance])}
  (fn [key _instance] key))

(defmethod halt-key :default
  [_instance-key _instance]
  nil)

(defn ^:private ^:async build-key
  [sys k v]
  (try
    (assoc sys k (await (init-key k (resolve-refs sys v))))
    (catch :default cause
      (throw (ex-info (str "Error on key " k " when building system")
                      {:reason ::init-failed
                       :key    k
                       :value  v
                       :system sys}
                      cause)))))

(defn- halt-order
  [sys ks]
  (let [config     (or (::origin (meta sys)) {})
        graph      (dependency-graph config)
        keyset     (config-keyset config ks)
        comparator (key-comparator graph)]
    (->> (set/union keyset (dep/transitive-dependents-set graph keyset))
         (filter #(contains? sys %))
         (sort comparator)
         (reverse)
         (vec))))

(defn ^:private ^:async halt-keys
  "Halt every key in ks, collecting failures instead of stopping at the
  first one. Returns a vector of `{:key :cause}` maps."
  [sys ks]
  (loop [failures []
         ks       ks]
    (if (seq ks)
      (let [k        (first ks)
            failures (try
                       (await (halt-key k (sys k)))
                       failures
                       (catch :default cause
                         (conj failures {:key k :cause cause})))]
        (recur failures (rest ks)))
      failures)))

(defn ^:private ^:async halt-silently
  [sys]
  (when (map? sys)
    (await (halt-keys sys (halt-order sys (keys sys)))))
  nil)

(defn ^:async init
  "Turn a config map into a running system map. Keys start in
  dependency order, each one awaited, with refs resolved to the running
  instances. With two args, only those keys start, plus whatever they
  depend on.

  Returns a promise of the system map. If a component fails to start,
  what already started is halted in reverse order, and the promise
  rejects with the original failure carrying `:reason ::init-failed`,
  the offending `:key`, and the partial `:system`."
  ([config]
   (init config (keys config)))
  ([config ks]
   (assert (map? config) "init needs a config map")
   (let [order (validate-init config ks)]
     (try
       (loop [sys (with-meta {} {::origin config})
              ks  order]
         (if (seq ks)
           (recur (await (build-key sys (first ks) (config (first ks))))
                  (rest ks))
           sys))
       (catch :default cause
         (await (halt-silently (:system (ex-data cause))))
         (throw cause))))))

(defn ^:async halt
  "Halt a running system map in reverse dependency order, awaiting each
  component. With two args, halt those keys plus whatever depends on them.

  Returns a promise of nil. Every component is given its chance to stop:
  failures are collected, and once everything ran, the promise rejects
  with `:reason ::halt-failed` and the `:failures` vector."
  ([sys]
   (halt sys (keys sys)))
  ([sys ks]
   (when-not (and (map? sys) (::origin (meta sys)))
     (throw (ex-info "halt needs a system map built by init."
                     {:reason ::not-a-system})))
   (let [failures (await (halt-keys sys (halt-order sys ks)))]
     (when (seq failures)
       (throw (ex-info (str "Failed to halt " (count failures) " component(s): "
                            (str/join ", " (map :key failures)))
                       {:reason   ::halt-failed
                        :failures failures}))))))
