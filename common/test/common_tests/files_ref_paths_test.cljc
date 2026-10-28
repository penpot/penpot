;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-ref-paths-test
  "Safety nets for the file and media references branching re-points.
  Every uuid a schema declares is either a reference the relink rewrites
  or a non-reference listed here, for three surfaces: shapes
  (`cfh/shape-ref-paths`), the rest of file data (`bm/remap-refs`), and
  change payloads (`bm/remap-changes`)."
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.files.changes :as ch]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.types.file :as ctf]
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

;; File data
;;
;; `bm/remap-refs` moves a whole document into another file's id space.
;; Shapes go through `cfh/relink-shape`, which the net above guards; this
;; net guards every other uuid of the document.

(defn- under?
  [prefix path]
  (and (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))

(def ^:private file-data-shape-subtrees
  "Shape maps inside file data, classified by the shape net. Component
  shapes are declared as plain `:map` (`ctn/schema:container`), so the
  walk never reaches them; the page ones it does are skipped here."
  [[:pages-index ::vals :objects ::vals]
   [:components ::vals :objects ::vals]])

(def ^:private file-data-inventory
  "Uuid paths of `ctf/schema:data` outside shapes. The schema omits the
  `:media` map that `:add-media` changes fill, so its entries are walked
  from `ctf/schema:media`."
  (delay
    (->> (concat (schema-uuid-paths ctf/schema:data)
                 (map #(into [:media] %)
                      (schema-uuid-paths [:map-of ::sm/uuid ctf/schema:media])))
         (remove (fn [path] (some #(under? % path) file-data-shape-subtrees)))
         (vec))))

(def ^:private file-data-ref-paths
  "File data paths `bm/remap-refs` rewrites: each names a file or a media
  object."
  #{;; file whose tokens lib the document uses
    [:tokens-source]
    ;; library colour image
    [:colors ::vals :image :id]
    ;; page default grid colours (`cfh/relink-page`)
    [:pages-index ::vals :default-grids :square :color :ref-file]
    [:pages-index ::vals :default-grids :row :color :ref-file]
    [:pages-index ::vals :default-grids :column :color :ref-file]
    ;; media objects: their id, and the file that owns the row
    [:media ::keys]
    [:media ::vals :id]
    [:media ::vals :file-id]})

(def ^:private file-data-undeclared-ref-paths
  "References `bm/remap-refs` rewrites that `ctf/schema:data` does not
  declare, so the inventory cannot find them; behaviour checks them."
  #{;; the document's own id
    [:id]
    ;; legacy grid colour `:file-id` (`cfh/legacy-shape-ref-paths`), and
    ;; `:saved-grids`, where default grids lived before migration
    ;; `legacy-55`
    [:pages-index ::vals :default-grids ::vals :color :file-id]
    [:pages-index ::vals :options :saved-grids ::vals :color :ref-file]
    [:pages-index ::vals :options :saved-grids ::vals :color :file-id]})

(def ^:private file-data-non-reference-paths
  "File data uuid paths that name no file and no media object."
  #{;; page identity and order
    [:pages :*]
    [:pages-index ::keys]
    [:pages-index ::vals :id]
    ;; shape ids keying the page objects
    [:pages-index ::vals :objects ::keys]
    ;; colour id inside its library
    [:pages-index ::vals :default-grids :square :color :ref-id]
    [:pages-index ::vals :default-grids :row :color :ref-id]
    [:pages-index ::vals :default-grids :column :color :ref-id]
    ;; prototype flows, guides, and comment positions of a page
    [:pages-index ::vals :flows ::keys]
    [:pages-index ::vals :flows ::vals :id]
    [:pages-index ::vals :flows ::vals :starting-frame]
    [:pages-index ::vals :guides ::keys]
    [:pages-index ::vals :guides ::vals :id]
    [:pages-index ::vals :guides ::vals :frame-id]
    [:pages-index ::vals :comment-thread-positions ::keys]
    [:pages-index ::vals :comment-thread-positions ::vals :frame-id]
    ;; asset identity
    [:colors ::keys]
    [:colors ::vals :id]
    [:components ::keys]
    [:components ::vals :id]
    [:components ::vals :objects ::keys]
    [:typographies ::keys]
    [:typographies ::vals :id]
    ;; storage objects behind a media object, not media objects
    [:media ::vals :media-id]
    [:media ::vals :thumbnail-id]})

(t/deftest every-file-data-uuid-is-classified
  (let [known   (set/union file-data-ref-paths file-data-non-reference-paths)
        missing (vec (remove known @file-data-inventory))]
    (t/is (empty? missing)
          (str "Unclassified uuid paths in `ctf/schema:data`: " (pr-str missing)
               ". Add each path that names a file or a media object to"
               " `file-data-ref-paths` in this test, and make `bm/remap-refs`"
               " rewrite it; add any other path to"
               " `file-data-non-reference-paths`."))))

(t/deftest file-data-classification-is-in-schema
  (let [found (set @file-data-inventory)]
    (t/is (empty? (remove found file-data-ref-paths))
          "`file-data-ref-paths` entries absent from the file data schema")
    (t/is (empty? (remove found file-data-non-reference-paths))
          "`file-data-non-reference-paths` entries absent from the file data schema")
    (t/is (empty? (filter found file-data-undeclared-ref-paths))
          "`file-data-undeclared-ref-paths` entries the schema now declares: move them to `file-data-ref-paths`")))

(def ^:private file-data-shape-probes
  "One reference per shape container, to show `bm/remap-refs` reaches
  shapes on pages and in deleted components."
  [[:pages-index ::vals :objects ::vals :component-file]
   [:components ::vals :objects ::vals :fills :* :fill-image :id]])

(defn- file-data-fixture
  "File data with a distinct uuid at every classified path."
  []
  (let [id       uuid/next
        page-id  (id)
        shape-id (id)
        comp-id  (id)
        cshape   (id)
        media-id (id)
        color-id (id)
        typo-id  (id)
        flow-id  (id)
        guide-id (id)
        thread   (id)
        grid     (fn [] {:color {:ref-id (id) :ref-file (id)}})]
    {:id            (id)
     :tokens-source (id)
     :pages         [page-id]
     :pages-index
     {page-id {:id            page-id
               :objects       {shape-id {:id shape-id :component-file (id)}}
               :default-grids {:square (assoc-in (grid) [:color :file-id] (id))
                               :row    (grid)
                               :column (grid)}
               :options       {:saved-grids {:square {:color {:ref-file (id)
                                                              :file-id  (id)}}}}
               :flows         {flow-id {:id flow-id :starting-frame (id)}}
               :guides        {guide-id {:id guide-id :frame-id (id)}}
               :comment-thread-positions {thread {:frame-id (id)}}}}
     :colors        {color-id {:id color-id :image {:id (id)}}}
     :components    {comp-id {:id      comp-id
                              :objects {cshape {:id    cshape
                                                :fills [{:fill-image {:id (id)}}]}}}}
     :typographies  {typo-id {:id typo-id}}
     :media         {media-id {:id           media-id
                               :file-id      (id)
                               :media-id     (id)
                               :thumbnail-id (id)}}}))

(defn- all-uuids
  "Every uuid in `x`, map keys included."
  [x]
  (into #{} (filter uuid?) (tree-seq coll? seq x)))

(t/deftest remap-refs-rewrites-file-data-refs-only
  (let [data      (file-data-fixture)
        ref-paths (concat file-data-ref-paths
                          file-data-undeclared-ref-paths
                          file-data-shape-probes)
        ;; every uuid is renamed, so a kept non-reference proves a skip
        id-map    (into {} (map (fn [id] [id (uuid/next)])) (all-uuids data))
        lookup    #(get id-map % %)
        result    (bm/remap-refs data id-map)]
    (t/testing "the fixture holds a uuid at every classified path"
      (t/is (empty? (remove #(some uuid? (locate data %))
                            (concat ref-paths file-data-non-reference-paths)))))
    (doseq [path ref-paths]
      (t/is (= (set (map lookup (locate data path)))
               (set (locate result path)))
            (str "reference not rewritten: " (pr-str path))))
    (doseq [path file-data-non-reference-paths]
      (t/is (= (set (locate data path))
               (set (locate result path)))
            (str "non-reference changed: " (pr-str path))))))

;; Change payloads
;;
;; `bm/remap-changes` moves a change vector into another file's id space.
;; Paths are `[type & path]`, one per `:multi` branch of `ch/schema:change`.
;; Subtrees another net guards, each through the relink it calls:
;;   [:add-obj :obj]           `cfh/relink-shape`: the shape net
;;   [:add-page :page]         `cfh/relink-page`: the shape net and the
;;                             default grids of the file data net; declared
;;                             `::sm/any`, so the walk never sees it
;;   [:add-component :objects] `cfh/relink-objects`: the shape net;
;;   [:mod-component :objects] undeclared, so the walk never sees them
;;   [:mod-obj :operations]    a `:set` value is any shape attr, declared
;;                             `::sm/any`: `remap-changes-relinks-mod-obj-set-values`
;; `change-subtree-probes` checks one reference under each.

(def ^:private change-shape-subtrees
  [[:add-obj :obj]])

(def ^:private change-inventory
  (delay
    (let [schema (m/deref-all (sm/schema ch/schema:change))]
      (->> (m/children schema)
           (mapcat (fn [[type _ child]]
                     (map #(into [type] %) (schema-uuid-paths child))))
           (remove (fn [path] (some #(under? % path) change-shape-subtrees)))
           (vec)))))

(def ^:private change-ref-paths
  "Change paths `bm/remap-changes` rewrites: each names a file or a media
  object."
  #{[:set-default-grid :params :color :ref-file]
    [:add-color :color :image :id]
    [:mod-color :color :image :id]
    [:add-media :object :id]
    [:add-media :object :file-id]
    [:mod-media :object :id]
    [:mod-media :object :file-id]
    [:del-media :id]
    [:set-tokens-source :file-id]
    [:set-tokens-source :library-id]})

(def ^:private change-undeclared-ref-paths
  "References `bm/remap-changes` rewrites that `ch/schema:change` does
  not declare."
  #{;; legacy grid colour `:file-id` (`cfh/legacy-shape-ref-paths`)
    [:set-default-grid :params :color :file-id]})

(def ^:private change-subtree-probes
  [[:add-obj :obj :component-file]
   [:add-page :page :objects ::vals :fills :* :fill-image :id]
   [:add-page :page :default-grids ::vals :color :ref-file]
   [:add-component :objects ::vals :component-file]
   [:mod-component :objects ::vals :component-file]])

(def ^:private change-non-reference-paths
  "Change uuid paths that name no file and no media object."
  #{;; entity identity: shape, page, component, colour, typography
    [:add-obj :id]
    [:mod-obj :id]
    [:del-obj :id]
    [:fix-obj :id]
    [:add-page :id]
    [:mod-page :id]
    [:del-page :id]
    [:mov-page :id]
    [:add-component :id]
    [:mod-component :id]
    [:del-component :id]
    [:restore-component :id]
    [:purge-component :id]
    [:add-color :color :id]
    [:mod-color :color :id]
    [:del-color :id]
    [:add-typography :typography :id]
    [:mod-typography :typography :id]
    [:del-typography :id]
    ;; container and tree position
    [:add-obj :page-id]
    [:add-obj :component-id]
    [:add-obj :frame-id]
    [:add-obj :parent-id]
    [:mod-obj :page-id]
    [:mod-obj :component-id]
    [:del-obj :page-id]
    [:del-obj :component-id]
    [:fix-obj :page-id]
    [:fix-obj :component-id]
    [:mov-objects :page-id]
    [:mov-objects :component-id]
    [:mov-objects :parent-id]
    [:reorder-children :page-id]
    [:reorder-children :component-id]
    [:reorder-children :parent-id]
    [:reg-objects :page-id]
    [:reg-objects :component-id]
    [:reg-objects :shapes :*]
    [:set-default-grid :page-id]
    [:restore-component :page-id]
    ;; component main instance and variant
    [:add-component :main-instance-id]
    [:add-component :main-instance-page]
    [:add-component :variant-id]
    [:mod-component :variant-id]
    ;; colour id inside its library
    [:set-default-grid :params :color :ref-id]
    ;; guides, flows, comment positions, and plugin data targets
    [:set-guide :page-id]
    [:set-guide :id]
    [:set-guide :params :id]
    [:set-guide :params :frame-id]
    [:set-flow :page-id]
    [:set-flow :id]
    [:set-flow :params :id]
    [:set-flow :params :starting-frame]
    [:set-comment-thread-position :comment-thread-id]
    [:set-comment-thread-position :page-id]
    [:set-comment-thread-position :frame-id]
    [:set-plugin-data :object-id]
    [:set-plugin-data :page-id]
    ;; storage objects behind a media object, not media objects
    [:add-media :object :media-id]
    [:add-media :object :thumbnail-id]
    [:mod-media :object :media-id]
    [:mod-media :object :thumbnail-id]
    ;; ids inside the tokens lib
    [:set-token :set-id]
    [:set-token :token-id]
    [:set-token :attrs :id]
    [:set-token-set :id]
    [:set-token-set :attrs :id]
    [:set-token-set :attrs :tokens ::vals :id]
    [:set-token-theme :id]
    [:set-token-theme :attrs :id]
    [:set-tokens-status :theme-ids ::set]
    [:set-tokens-status :set-ids ::set]})

(t/deftest every-change-uuid-is-classified
  (let [known   (set/union change-ref-paths change-non-reference-paths)
        missing (vec (remove known @change-inventory))]
    (t/is (empty? missing)
          (str "Unclassified uuid paths in `ch/schema:change`, as [type & path]: "
               (pr-str missing)
               ". Add each path that names a file or a media object to"
               " `change-ref-paths` in this test, and make `bm/remap-changes`"
               " rewrite it for that change type; add any other path to"
               " `change-non-reference-paths`."))))

(t/deftest change-classification-is-in-schema
  (let [found (set @change-inventory)]
    (t/is (empty? (remove found change-ref-paths))
          "`change-ref-paths` entries absent from the change schema")
    (t/is (empty? (remove found change-non-reference-paths))
          "`change-non-reference-paths` entries absent from the change schema")
    (t/is (empty? (filter found change-undeclared-ref-paths))
          "`change-undeclared-ref-paths` entries the schema now declares: move them to `change-ref-paths`")))

(defn- build-at
  "A minimal map holding `v` at `path`."
  [path v]
  (if-let [[step & more] (seq path)]
    (cond
      (= :* step)    [(build-at more v)]
      (= ::set step) #{(build-at more v)}
      (= ::keys step) {v {}}
      (= ::vals step) {:entry (build-at more v)}
      :else          {step (build-at more v)})
    v))

(defn- remap-at
  "Plant `old` at `[type & path]` in a change of that type, remap it with
  `{old new}`, and return the values found at the path afterwards."
  [[type & path] old new]
  (let [change (assoc (build-at path old) :type type)
        [out]  (bm/remap-changes [change] {old new})]
    (locate out path)))

(t/deftest remap-changes-rewrites-refs
  (doseq [path (concat change-ref-paths
                       change-undeclared-ref-paths
                       change-subtree-probes)]
    (let [old (uuid/next)
          new (uuid/next)]
      (t/is (= [new] (remap-at path old new))
            (str "reference not rewritten: " (pr-str path))))))

(t/deftest remap-changes-keeps-non-references
  (doseq [path change-non-reference-paths]
    (let [old (uuid/next)]
      (t/is (= [old] (remap-at path old (uuid/next)))
            (str "non-reference changed: " (pr-str path))))))

(t/deftest remap-changes-relinks-mod-obj-set-values
  ;; `shape-set-ops` emits one `:set` per differing attr, so a reference
  ;; travels as the value of its top-level shape attr
  (doseq [[attr paths] (group-by first @all-ref-paths)]
    (let [shape  (reduce #(plant %1 %2 uuid/next) {} paths)
          id-map (into {} (map (fn [id] [id (uuid/next)])) (uuids-at shape paths))
          change {:type       :mod-obj
                  :id         (uuid/next)
                  :page-id    (uuid/next)
                  :operations [{:type :set :attr attr :val (get shape attr)}]}
          [out]  (bm/remap-changes [change] id-map)
          result {attr (get-in out [:operations 0 :val])}]
      (t/is (seq id-map) (str "nothing planted under " attr))
      (doseq [path paths]
        (t/is (= (mapv #(get id-map % %) (locate shape path))
                 (vec (locate result path)))
              (str "`:set " attr "` value not relinked at " (pr-str path)))))))

(t/deftest remap-changes-relinks-mod-obj-assign-values
  ;; an `:assign` carries a partial shape: every ref path in one value
  (let [paths  @all-ref-paths
        shape  (reduce #(plant %1 %2 uuid/next) {} paths)
        id-map (into {} (map (fn [id] [id (uuid/next)])) (uuids-at shape paths))
        change {:type       :mod-obj
                :id         (uuid/next)
                :page-id    (uuid/next)
                :operations [{:type :assign :value shape}]}
        [out]  (bm/remap-changes [change] id-map)
        result (get-in out [:operations 0 :value])]
    (doseq [path paths]
      (t/is (= (mapv #(get id-map % %) (locate shape path))
               (vec (locate result path)))
            (str "`:assign` value not relinked at " (pr-str path))))))
