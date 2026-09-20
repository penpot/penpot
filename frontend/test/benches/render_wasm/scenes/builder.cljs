;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.builder
  "Scoped construction for renderer benchmark fixtures.

  A fixture scope owns one seeded generator, one object map, and one label
  map. `(shape ...)` calls add shapes to the tree.

  Construction is synchronous by design: the scope is carried by a dynamic
  var, so `let`, `doseq` and functions defined outside the scope all work
  inside a fixture, while fixture scopes cannot nest and body exceptions
  unwind without leaving state behind.

  Defaults follow the same theme: `*defaults*` maps a shape type to an
  attribute generator map, bound by the `fixture` macro from the scope
  params. Per-shape attrs are merged over the generated values, so a recipe
  can override anything locally and rebind `*defaults*` around a section.

  Frame/group scopes are ticket18; booleans ticket19; paths ticket07/08."
  (:require
   [app.common.types.color :as clr]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [benches.render-wasm.scenes.common :as sc]))

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
      (let [s (swap! state (fn [s] (bit-or (+ s 0x6d2b79f5) 0)))
            v (js/Math.imul (bit-xor s (unsigned-bit-shift-right s 15))
                            (bit-or s 1))
            v (bit-xor v (+ v (js/Math.imul (bit-xor v (unsigned-bit-shift-right v 7))
                                            (bit-or v 61))))
            v (bit-xor v (unsigned-bit-shift-right v 14))]
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

(defn- ensure-state
  [state]
  (or state
      (throw (ex-info "rect used outside a fixture scope"
                      {:type ::outside-scope}))))

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
           :refs {}})))

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

(defn- normalize-label-and-attrs
  "Resolves the one-argument `rect` form at runtime: a map is attrs, any
  other value is a label. Rejects label types outside keywords and
  vectors."
  [label attrs]
  (cond
    (and (map? label) (empty? attrs)) [nil label]
    (nil? label) [nil attrs]
    (or (keyword? label) (vector? label)) [label attrs]
    :else (throw (ex-info "rect label must be a keyword or vector"
                          {:type ::invalid-label
                           :label label}))))

(defn rect!
  "Adds one rectangle to the scope and returns its uuid. This is the primitive
  behind the `rect` macro."
  [state defaults label attrs]
  (let [state         (ensure-state state)
        [label attrs] (normalize-label-and-attrs label attrs)
        rng           (:rng @state)
        attrs         (resolve-attrs rng (:rect defaults) attrs)
        id            (rng-uuid rng)
        shape         (cts/setup-shape (merge attrs
                                              {:id id
                                               :type :rect
                                               :parent-id uuid/zero
                                               :frame-id uuid/zero}))]
    (register-ref! state label id)
    (swap! state
           (fn [scope]
             (-> scope
                 (assoc-in [:objects id] shape)
                 (update-in [:objects uuid/zero :shapes] conj id))))
    id))

(defn finish!
  "Validates the scope snapshot and returns it."
  [state]
  (let [instance {:objects (:objects @state)
                  :refs (:refs @state)}]
    (sc/validate! instance)
    instance))
