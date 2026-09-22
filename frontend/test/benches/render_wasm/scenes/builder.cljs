;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.builder
  "Scoped construction for renderer benchmark fixtures.

  A fixture scope owns one seeded generator, one object map, and one label
  map. Shape constructors add themselves to the current parent: `rect`, and
  the `frame`/`group`/`bool` container scopes. The scope returns a validated
  snapshot in the ticket02 contract `{:objects ... :refs ...}`.

  Construction is synchronous by design: the scope and the current parent
  are carried by dynamic vars, so `let`, `doseq` and functions defined
  outside the scope all work inside a fixture. Fixture scopes cannot nest
  and body exceptions unwind without leaving state behind. A container whose
  body throws is marked unfinished and `finish!` rejects the instance, so a
  caught exception cannot silently produce a half-built scene.

  Defaults follow the same theme: `*defaults*` maps a shape type to an
  attribute generator map, bound by the `fixture` macro from the scope
  params. Per-shape attrs are merged over the generated values, so a recipe
  can override anything locally and rebind `*defaults*` around a section.

  Coordinates stay page-absolute: nesting supplies ownership and order, not
  translation. Frames keep authored bounds; groups derive bounds from their
  children inside out; a masked group takes the geometry of its first child.
  Booleans derive content and geometry from their children with the canonical
  path engine and inherit style from the head child unless attrs supply it.

  Masked groups and the frame/group/bool scopes live here; paths ticket07/08."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.color :as clr]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.common :as common]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Poor-man's seeded PRNG
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; WTF? clj has no seedable PRNG!? `rand`/`rand-int` call Math.random which is
;; unseeded. I can't find any RNG wich takes a seed among the deps.
;; We implement mulberry32 here instead.
(defn- make-random
  "Returns a zero-arg function that draws a float in `[0, 1)` from the seeded.

  This is Mulberry32 which reportedly misses about 1/3 of the range. Do NOT USE
  for anything requiring actual random numbers!
  "
  [seed]
  (let [state (atom (bit-or seed 0))]
    (fn []
      (let [s    (swap! state (fn [s] (bit-or (+ s 0x6d2b79f5) 0)))
            v    (js/Math.imul (bit-xor s (unsigned-bit-shift-right s 15))
                               (bit-or s 1))
            v    (bit-xor v (+ v (js/Math.imul (bit-xor v (unsigned-bit-shift-right v 7))
                                               (bit-or v 61))))
            v    (bit-xor v (unsigned-bit-shift-right v 14))]
        (/ (unsigned-bit-shift-right v 0) 4294967296)))))

(defn rng-float
  "Draws a float in `[min, max)` from the scope random source."
  [rng min max]
  (+ min (* (rng) (- max min))))

(defn rng-int
  "Draws an integer in `[min, max)` from the scope random source."
  [rng min max]
  (js/Math.floor (rng-float rng min max)))

(defn- round3
  [value]
  (/ (js/Math.round (* 1000 value)) 1000))

(defn rng-uuid
  "Draws a deterministic uuid from two 32-bit values of the random source."
  [rng]
  (uuid/custom (rng-int rng 0 4294967296)
               (rng-int rng 0 4294967296)))

(defn- random-hex-color
  [rng]
  (clr/rgb->hex [(rng-int rng 0 256) (rng-int rng 0 256) (rng-int rng 0 256)]))

(defn- random-opacity
  [rng]
  (min 0.999 (round3 (rng-float rng 0.1 1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attribute generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn gen-int
  "Integer generator over `[min, max)`."
  [min max]
  (fn [rng] (rng-int rng min max)))

(defn gen-float
  "Float generator rounded to three decimals so snapshots stay readable."
  [min max]
  (fn [rng] (round3 (rng-float rng min max))))

(defn gen-hex-color
  "Generator of a random `#rrggbb` string."
  []
  (fn [rng] (random-hex-color rng)))

(defn gen-one-of
  "Generator that picks one value per draw. Rejects an empty collection."
  [values]
  (when (empty? values)
    (throw (ex-info "gen-one-of requires at least one value"
                    {:type ::no-values})))
  (fn [rng] (nth values (rng-int rng 0 (count values)))))

(defn gen-fill
  "Generator of one translucent solid fill."
  []
  (fn [rng]
    {:fill-color (random-hex-color rng)
     :fill-opacity (random-opacity rng)}))

(defn gen-stroke
  "Centered stroke like the interim rectangle workload."
  [width]
  (fn [rng]
    {:stroke-width width
     :stroke-alignment :center
     :stroke-color (random-hex-color rng)
     :stroke-opacity (random-opacity rng)}))

(defn gen-vector
  "Composes generators into a generator of a vector, one value per
  generator."
  [& generators]
  (fn [rng] (mapv #(% rng) generators)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scope state
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *state*
  "Current fixture scope; bound by the `fixture` macro."
  nil)

(def ^:dynamic *defaults*
  "Per-shape-type attribute generator maps bound by the `fixture` macro,
  e.g. `{:rect {:x (gen-int 0 1920) ...}}`. Values are literals or one-arg
  functions of the scope random source."
  {})

(def ^:dynamic *parent*
  "Current insertion point as `{:id parent-id :frame-id containing-frame}`;
  bound by the container scopes, `uuid/zero` at the top."
  {:id uuid/zero :frame-id uuid/zero})

(defn- check-seed!
  [seed]
  (when-not (and (integer? seed) (<= 0 seed) (< seed 4294967296))
    (throw (ex-info "fixture scope requires an integer :seed in [0, 2^32)"
                    {:type ::invalid-seed
                     :seed seed}))))

(defn start
  "Starts a fixture scope. Returns the scope state atom."
  [params]
  (when (some? *state*)
    (throw (ex-info "fixture scopes cannot nest"
                    {:type ::nested-scope})))
  (check-seed! (:seed params))
  (let [seed (:seed params)
        root (cts/setup-shape (merge (:root params)
                                     {:id uuid/zero
                                      :type :frame
                                      :name "Root Frame"
                                      :parent-id uuid/zero
                                      :frame-id uuid/zero
                                      :shapes []}))]
    (atom {:seed seed
           :rng (make-random seed)
           :objects {uuid/zero root}
           :refs {}
           :unfinished {}})))

(defn- ensure-state
  [state]
  (or state
      (throw (ex-info "shape used outside a fixture scope"
                      {:type ::outside-scope}))))

(defn- resolve-attr
  [rng spec]
  (if (fn? spec) (spec rng) spec))

(defn- resolve-attrs
  [rng defaults attrs]
  (merge (reduce-kv (fn [result key spec]
                      (assoc result key (resolve-attr rng spec)))
                    {}
                    defaults)
         attrs))

(defn- register-ref!
  [state label id]
  (when (some? label)
    (when (contains? (:refs @state) label)
      (throw (ex-info (str "duplicate fixture label: " label)
                      {:type ::duplicate-label
                       :label label})))
    (swap! state assoc-in [:refs label] id)))

(defn- add-object!
  "Inserts `shape` under the current parent and registers `label`."
  [state label shape]
  (let [{:keys [id frame-id]} *parent*]
    (register-ref! state label (:id shape))
    (swap! state
           (fn [scope]
             (ctst/add-shape (:id shape) shape scope frame-id id nil true)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape constructors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- normalize-label-and-attrs
  "Resolves the one-argument `rect`/`circle` forms at runtime: a map is attrs,
  any other value is a label. Rejects label types outside keywords and vectors."
  [label attrs]
  (cond
    (and (map? label) (empty? attrs)) [nil label]
    (nil? label) [nil attrs]
    (or (keyword? label) (vector? label)) [label attrs]
    :else (throw (ex-info "rect label must be a keyword or vector"
                          {:type ::invalid-label
                           :label label}))))

(defn rect!
  "Adds one rectangle to the scope and returns its uuid.
  Runtime primitive behind the `rect` macro."
  [state defaults label attrs]
  (let [state         (ensure-state state)
        [label attrs] (normalize-label-and-attrs label attrs)
        rng           (:rng @state)
        attrs         (resolve-attrs rng (:rect defaults) attrs)
        id            (rng-uuid rng)
        shape         (cts/setup-shape (merge attrs
                                              {:id id
                                               :type :rect}))]
    (add-object! state label shape)
    id))

(defn circle!
  "Adds one circle to the scope and returns its uuid.
  Runtime primitive behind the `circle` macro."
  [state defaults label attrs]
  (let [state         (ensure-state state)
        [label attrs] (normalize-label-and-attrs label attrs)
        rng           (:rng @state)
        attrs         (resolve-attrs rng (:circle defaults) attrs)
        id            (rng-uuid rng)
        shape         (cts/setup-shape (merge attrs
                                              {:id id
                                               :type :circle}))]
    (add-object! state label shape)
    id))

(def ^:private frame-bounds-keys
  "Frames do not derive their bounds from children."
  [:x :y :width :height])

(def ^:private group-geometry-keys
  "Group geometry derives from its children."
  [:x :y :width :height :selrect :points])

(def ^:private bool-geometry-keys
  "Bool geometry, content and placement derive from its children."
  [:x :y :width :height :selrect :points :content])

(def ^:private bool-transform-keys
  "Construction does not accept transforms: `path/update-geometry` would
  pivot them on the empty pre-finalization selrect."
  [:transform :transform-inverse :rotation :flip-x :flip-y])

(defn- reject-attrs!
  "Throws when `attrs` carries any of `attr-keys`. Shared by containers
  whose geometry or content derives from their children."
  [attrs id error-type message attr-keys]
  (doseq [key attr-keys]
    (when (some? (get attrs key))
      (throw (ex-info message
                      {:type error-type
                       :id id
                       :key key})))))

(defn- setup-frame
  [id attrs]
  (when-not (map? attrs)
    (throw (ex-info "frame attrs must be a map; the label form takes the attrs map next"
                    {:type ::invalid-attrs
                     :id id})))
  (doseq [key frame-bounds-keys]
    (let [value (get attrs key)]
      (cond
        (nil? value)
        (throw (ex-info "frame requires :x :y :width :height"
                        {:type ::frame-bounds
                         :id id
                         :missing key}))

        (not (d/num? value))
        (throw (ex-info "frame bounds must be numbers"
                        {:type ::frame-bounds
                         :id id
                         :invalid key})))))
  (cts/setup-shape (assoc attrs :id id :type :frame)))

(defn- setup-group
  [id attrs]
  (when-not (map? attrs)
    (throw (ex-info "group attrs must be a map; the label form takes the attrs map next"
                    {:type ::invalid-attrs
                     :id id})))
  (reject-attrs! attrs id ::group-geometry
                 "group derives its geometry from children"
                 group-geometry-keys)
  (cts/setup-shape (assoc attrs :id id :type :group)))

(defn- setup-bool
  [id attrs]
  (when-not (map? attrs)
    (throw (ex-info "bool attrs must be a map; the label form takes the attrs map next"
                    {:type ::invalid-attrs
                     :id id})))
  (reject-attrs! attrs id ::bool-geometry
                 "bool derives its geometry and content from children"
                 bool-geometry-keys)
  (reject-attrs! attrs id ::bool-transform
                 "bool construction does not accept transforms"
                 bool-transform-keys)
  (when-not (contains? cts/bool-types (:bool-type attrs))
    (throw (ex-info "bool requires :bool-type :union :difference :intersection or :exclude"
                    {:type ::bool-type
                     :id id
                     :bool-type (:bool-type attrs)
                     :supported cts/bool-types})))
  (cts/setup-shape (assoc attrs :id id :type :bool)))

(defn- bool-head
  "Canonical style head: the first child is the base for `:difference`, the
  last child otherwise."
  [bool-shape children]
  (if (= :difference (:bool-type bool-shape))
    (first children)
    (last children)))

(defn- finalize-bool!
  [state id]
  (let [scope    @state
        bool     (get-in scope [:objects id])
        children (mapv (:objects scope) (:shapes bool))]
    (when (empty? children)
      (throw (ex-info "bool requires at least one child"
                      {:type ::empty-bool
                       :id id})))
    (when-let [frame (first (filter cfh/frame-shape? children))]
      (throw (ex-info "bool children cannot be frames"
                      {:type ::frame-in-bool
                       :id id
                       :child (:id frame)})))
    (let [inherited (d/without-nils
                     (select-keys (bool-head bool children)
                                  path/bool-style-properties))
          supplied  (d/without-nils
                     (select-keys (get-in scope [:container-attrs id])
                                  path/bool-style-properties))
          bool      (-> bool (merge inherited) (merge supplied))

          ;; `path/update-bool-shape` dispatches to `path/wasm:calc-bool-content`,
          ;; the renderer override, so fixture content would depend on renderer
          ;; initialization and A/B configuration. Compose the pure helpers:
          ;; fixture generation is untimed and must not vary with the renderer
          ;; being compared.
          content   (path/calc-bool-content bool (:objects scope))
          bool      (path/update-geometry bool content)]
      (swap! state assoc-in [:objects id] bool))))

(defn- finalize-group!
  [state id]
  (let [scope    @state
        group    (get-in scope [:objects id])
        children (mapv (:objects scope) (:shapes group))]
    (when (empty? children)
      (throw (ex-info "group requires at least one child"
                      {:type ::empty-group
                       :id id})))
    (let [group (if (:masked-group group)
                  (gsh/update-mask-selrect group children)
                  (gsh/update-group-selrect group children))]
      (swap! state assoc-in [:objects id] group))))

(def ^:private container-specs
  "Per-container behavior: how to build it, which parent scope it opens,
  and how to finalize it after the body. `:keep-attrs?` records the raw
  attrs in the scope so the finalizer can let supplied styles win over
  inherited ones."
  {:frame {:setup setup-frame
           :parent (fn [id _parent] {:id id :frame-id id})
           :finalize (fn [_state _id] nil)}
   :group {:setup setup-group
           :parent (fn [id parent] {:id id :frame-id (:frame-id parent)})
           :finalize finalize-group!}
   :bool {:setup setup-bool
          :parent (fn [id parent] {:id id :frame-id (:frame-id parent)})
          :finalize finalize-bool!
          :keep-attrs? true}})

(defn with-container
  "Creates a frame, group or bool, runs `thunk` with the new parent in
  scope, finalizes the containers that derive state from children, and
  returns the container uuid.

  Any exception from the body or the finalizer marks the container
  unfinished and propagates; `finish!` then rejects the instance even if the
  caller catches the exception."
  [type attrs label thunk]
  (let [{:keys [setup parent finalize keep-attrs?]}
        (or (get container-specs type)
            (throw (ex-info "unsupported container type"
                            {:type ::invalid-container
                             :container-type type})))
        state (ensure-state *state*)
        id    (rng-uuid (:rng @state))
        shape (setup id attrs)]
    (when keep-attrs?
      (swap! state assoc-in [:container-attrs id] attrs))
    (add-object! state label shape)
    (binding [*parent* (parent id *parent*)]
      (try
        (thunk)
        (finalize state id)
        (catch :default cause
          (swap! state assoc-in [:unfinished id] true)
          (throw cause))))
    id))

(defn finish!
  "Validates the scope snapshot and returns it."
  [state]
  (let [{:keys [objects refs unfinished]} @state]
    (when (seq unfinished)
      (throw (ex-info "fixture contains an unfinished container"
                      {:type ::unfinished-container
                       :ids (vec (keys unfinished))})))
    (let [instance {:objects objects
                    :refs refs}]
      (common/validate! instance)
      instance)))
