;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-ref-paths-test
  "Safety net for `cfh/shape-ref-paths`: every uuid the shape schema
  declares is either a file or media reference listed there, or a
  non-reference listed here."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [clojure.test :as t]
   [malli.core :as m]))

;; Path steps beyond the `cfh/shape-ref-paths` language. They name
;; places a reference spec cannot reach today, so a uuid found under
;; one always fails classification until the language grows.
;;   ::set   every element of a set
;;   ::keys  every key of a map-of
;;   ::vals  every value of a map-of

(def ^:private uuid-types
  #{::sm/uuid :uuid})

(defn- collapse-text-tree
  "Replace each run of `:children :*` steps with `::cfh/tree`. The text
  content schema spells its three levels out; the reference spec walks
  them as one recursive tree."
  [path]
  (loop [in  (seq path)
         out []]
    (if-let [[step & more] in]
      (if (and (= :children step) (= :* (first more)))
        (recur (next more) (if (= ::cfh/tree (peek out)) out (conj out ::cfh/tree)))
        (recur more (conj out step)))
      out)))

(defn- schema-uuid-paths
  "All paths under `schema` whose value schema is a uuid. Throws on a
  container type the walk does not know, so a new one cannot hide a
  uuid. A ref met again inside its own subtree stops the walk there."
  [schema]
  (letfn [(walk [s path seen]
            (let [form (when (sm/ref? s) (m/form s))]
              (when-not (and form (contains? seen form))
                (let [seen (cond-> seen form (conj form))
                      s    (m/deref-all s)
                      type (m/type s)]
                  (cond
                    (contains? uuid-types type)
                    [path]

                    (= :map type)
                    (mapcat (fn [[k _ child]] (walk child (conj path k) seen))
                            (m/children s))

                    (= :multi type)
                    (mapcat (fn [[_ _ child]] (walk child path seen))
                            (m/children s))

                    (contains? #{:and :or :maybe} type)
                    (mapcat #(walk % path seen) (m/children s))

                    (contains? #{:vector :sequential} type)
                    (walk (first (m/children s)) (conj path :*) seen)

                    (= :set type)
                    (walk (first (m/children s)) (conj path ::set) seen)

                    (= :map-of type)
                    (let [[ks vs] (m/children s)]
                      (concat (walk ks (conj path ::keys) seen)
                              (walk vs (conj path ::vals) seen)))

                    (some m/schema? (m/children s))
                    (throw (ex-info "unhandled schema type in uuid inventory"
                                    {:type type :path path}))

                    :else
                    nil)))))]
    (->> (walk (sm/schema schema) [] #{})
         (map collapse-text-tree)
         (distinct)
         (vec))))

(def ^:private inventory
  (delay (schema-uuid-paths cts/schema:shape)))

(def ^:private non-reference-paths
  "Uuid paths that name no file and no media row; relinking keeps them."
  #{;; identity
    [:id]
    ;; tree
    [:parent-id]
    [:frame-id]
    [:page-id]
    [:shapes :*]
    ;; component
    [:component-id]
    [:shape-ref]
    [:variant-id]
    ;; asset: colour or typography id inside its library
    [:fills :* :fill-color-ref-id]
    [:strokes :* :stroke-color-ref-id]
    [:shadow :* :color :ref-id]
    [:grids :* :params :color :ref-id]
    [:content ::cfh/tree :fills :* :fill-color-ref-id]
    [:content ::cfh/tree :typography-ref-id]
    [:position-data :* :fills :* :fill-color-ref-id]
    ;; effect
    [:shadow :* :id]
    [:blur :id]
    [:background-blur :id]
    ;; prototype
    [:interactions :* :destination]
    [:interactions :* :position-relative-to]
    ;; layout
    [:layout-grid-cells ::keys]
    [:layout-grid-cells ::vals :id]
    [:layout-grid-cells ::vals :shapes :*]})

(defn- unclassified-paths
  [paths ref-specs]
  (let [known (into non-reference-paths (map :path) ref-specs)]
    (vec (remove known paths))))

(defn- stale-paths
  [paths ref-specs]
  (let [found (set paths)]
    (->> ref-specs
         (map :path)
         (remove found)
         (vec))))

(t/deftest every-schema-uuid-is-classified
  (let [missing (unclassified-paths @inventory cfh/shape-ref-paths)]
    (t/is (empty? missing)
          (str "Unclassified uuid paths in the shape schema: " (pr-str missing)
               ". Add each path to `cfh/shape-ref-paths` if it names a file or"
               " a media row, else to `non-reference-paths` in this test."))))

(t/deftest every-ref-path-is-in-schema
  (let [stale (stale-paths @inventory cfh/shape-ref-paths)]
    (t/is (empty? stale)
          (str "`cfh/shape-ref-paths` entries absent from the shape schema: "
               (pr-str stale)
               ". Fix the path, or move it to `cfh/legacy-shape-ref-paths`."))))

(t/deftest non-reference-paths-are-in-schema
  (let [found (set @inventory)]
    (t/is (empty? (remove found non-reference-paths)))))

;; Behaviour

(defn- locate
  "Values at `path` in `x`, one per concrete location."
  [x path]
  (if-let [[step & more] (seq path)]
    (cond
      (= :* step)
      (when (sequential? x) (mapcat #(locate % more) x))

      (= ::set step)
      (when (set? x) (mapcat #(locate % more) x))

      (= ::keys step)
      (when (map? x) (mapcat #(locate % more) (keys x)))

      (= ::vals step)
      (when (map? x) (mapcat #(locate % more) (vals x)))

      (= ::cfh/tree step)
      (when (map? x) (mapcat #(locate % more) (tree-seq map? :children x)))

      :else
      (when (map? x)
        (when-some [v (get x step)] (locate v more))))
    [x]))

(defn- plant
  "Set a value from `next-id` at every location of `path` in `x`.
  Missing maps and vectors on the way are created with one element,
  so every shape type carries every reference; a non-map where the
  path wants a map (a path shape's binary `:content`) is left alone."
  [x path next-id]
  (let [[step & more] path]
    (cond
      (= :* step)
      (cond
        (nil? x)    [(plant nil more next-id)]
        (vector? x) (mapv #(plant % more next-id) x)
        :else       x)

      (= ::cfh/tree step)
      (letfn [(node [n]
                (let [n (plant n more next-id)]
                  (cond-> n
                    (vector? (:children n)) (update :children #(mapv node %)))))]
        (if (or (nil? x) (map? x)) (node x) x))

      (nil? x)
      (plant {} path next-id)

      (not (map? x))
      x

      (empty? more)
      (assoc x step (next-id))

      :else
      (assoc x step (plant (get x step) more next-id)))))

(def ^:private all-ref-paths
  (delay (into (mapv :path cfh/shape-ref-paths)
               (map :path)
               cfh/legacy-shape-ref-paths)))

(defn- uuids-at
  [shape paths]
  (into #{} (comp (mapcat #(locate shape %)) (filter uuid?)) paths))

(def ^:private generated-shapes
  (delay
    (let [gen (sg/generator cts/schema:shape)]
      (mapv #(sg/generate gen {:seed % :size 10}) (range 60)))))

(t/deftest relink-shape-rewrites-refs-only
  (let [ref-paths @all-ref-paths
        planted   (mapv (fn [shape]
                          (reduce #(plant %1 %2 uuid/next) shape ref-paths))
                        @generated-shapes)]
    (t/testing "every ref path is exercised"
      (let [hit (into #{}
                      (mapcat (fn [shape]
                                (filter #(some uuid? (locate shape %)) ref-paths)))
                      planted)]
        (t/is (empty? (remove hit ref-paths)))))

    (doseq [shape planted]
      (let [scanned  (into ref-paths @inventory)
            old-refs (uuids-at shape ref-paths)
            renames  (into {}
                           (map (fn [id] [id (uuid/next)]))
                           (uuids-at shape scanned))
            lookup   #(get renames % %)
            result   (cfh/relink-shape shape lookup)]
        (t/testing (str (:type shape) " " (:id shape))
          (t/is (empty? (set/intersection old-refs (uuids-at result scanned)))
                "no planted id remains")
          (t/is (empty? (remove #(= (mapv lookup (locate shape %))
                                    (vec (locate result %)))
                                ref-paths))
                "ref paths not rewritten through lookup")
          (t/is (empty? (remove #(= (vec (locate shape %))
                                    (vec (locate result %)))
                                non-reference-paths))
                "non-reference paths changed")
          (let [same (identical? shape (cfh/relink-shape shape identity))]
            (t/is same "identity lookup returns the input object")))))))

(t/deftest relink-objects-preserves-identity
  (let [objects (into {} (map (juxt :id identity)) @generated-shapes)]
    (t/is (identical? objects (cfh/relink-objects objects identity)))))
