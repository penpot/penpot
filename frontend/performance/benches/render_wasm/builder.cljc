;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.builder
  "Seeded construction of benchmark scenes.

  A `scene` consists of one seeded number generator, one object map, and one
  label map.

  Shape and container calls attach objects to the current parent in evaluation
  order. Coordinates are page-relative. Frames use authored bounds; groups and
  booleans derive geometry from their children after the body completes.

  `scene` accepts a required :seed, optional :root attributes and :defaults.
  It returns a validated snapshot with :objects and :refs. Labels name objects
  within that snapshot. A container whose body or finalizer throws becomes
  unfinished, and `finish!` rejects the snapshot.

  Default attributes follow the same theme: `*defaults*` maps a shape type to
  an attribute generator map, bound by the `scene` macro from the scope
  params. Per-shape attrs are merged over the generated values, so a recipe
  can override values locally and rebind `*defaults*` around a section.

  Ordinary :require loads the macros and runtime helpers. Scene bodies support
  let, doseq and calls to functions defined outside the scope."
  #?(:cljs (:require-macros [benches.render-wasm.builder]))
  #?(:cljs
     (:require
      [app.common.data :as d]
      [app.common.files.helpers :as cfh]
      [app.common.geom.shapes :as gsh]
      [app.common.types.path :as path]
      [app.common.types.shape :as cts]
      [app.common.types.shape-tree :as ctst]
      [app.common.uuid :as uuid]
      [benches.render-wasm.random :as rnd :refer
       [rng-float rng-hex-color rng-int rng-opacity rng-uuid]]
      [benches.render-wasm.snapshot :as snapshot])))

#?(:clj
   (do
     (defn- emit-shape
       [constructor label attrs]
       `(~constructor
         benches.render-wasm.builder/*state*
         benches.render-wasm.builder/*defaults*
         ~label
         ~attrs))

     (defn- emit-container
       [type label-or-attrs body]
       (let [labeled? (or (keyword? label-or-attrs) (vector? label-or-attrs))
             attrs    (if labeled? (first body) label-or-attrs)
             label    (when labeled? label-or-attrs)
             body     (if labeled? (rest body) body)]
         `(benches.render-wasm.builder/with-container
            ~type ~attrs ~label (fn [] ~@body))))

     (defmacro scene
       "Runs `body` in a scene scope and returns the validated snapshot."
       [params & body]
       `(let [params# ~params
              state#  (benches.render-wasm.builder/start params#)]
          (binding [benches.render-wasm.builder/*state* state#
                    benches.render-wasm.builder/*defaults* (:defaults params#)]
            ~@body
            (benches.render-wasm.builder/finish! state#))))

     (defmacro rect
       "Adds a rectangle and returns its uuid.

       Forms: (rect), (rect attrs), (rect label), (rect label attrs).
       A single map argument supplies attributes; keywords and vectors label
       the object in :refs. Attributes override the generated defaults."
       ([]
        (emit-shape 'benches.render-wasm.builder/rect! nil {}))
       ([label-or-attrs]
        (emit-shape 'benches.render-wasm.builder/rect! label-or-attrs {}))
       ([label attrs]
        (emit-shape 'benches.render-wasm.builder/rect! label attrs)))

     (defmacro path
       "Adds a path and returns its uuid.

       Forms: (path), (path attrs), (path label), (path label attrs).
       A single map argument supplies attributes; keywords and vectors label
       the object in :refs. Attributes override the generated defaults."
       ([]
        (emit-shape 'benches.render-wasm.builder/path! nil {}))
       ([label-or-attrs]
        (emit-shape 'benches.render-wasm.builder/path! label-or-attrs {}))
       ([label attrs]
        (emit-shape 'benches.render-wasm.builder/path! label attrs)))

     (defmacro circle
       "Adds an ellipse and returns its uuid.

       Forms: (circle), (circle attrs), (circle label), (circle label attrs).
       A single map argument supplies attributes; keywords and vectors label
       the object in :refs. Attributes override the generated defaults."
       ([]
        (emit-shape 'benches.render-wasm.builder/circle! nil {}))
       ([label-or-attrs]
        (emit-shape 'benches.render-wasm.builder/circle! label-or-attrs {}))
       ([label attrs]
        (emit-shape 'benches.render-wasm.builder/circle! label attrs)))

     (defmacro frame
       "Adds a frame, runs `body` with the frame as parent, and returns its uuid.

       Forms: (frame attrs & body), (frame label attrs & body).
       A keyword or vector form supplies a label in :refs. Attributes must
       carry numeric :x :y :width :height. Children use page-relative
       coordinates. Frames allow an empty body."
       [label-or-attrs & body]
       (emit-container :frame label-or-attrs body))

     (defmacro group
       "Adds a group, runs `body` with the group as parent, and returns its uuid.

       Forms: (group attrs & body), (group label attrs & body).
       A keyword or vector form supplies a label in :refs. Groups derive their
       geometry from children and require at least one child. With
       :masked-group true, the first child supplies the mask geometry."
       [label-or-attrs & body]
       (emit-container :group label-or-attrs body))

     (defmacro bool
       "Adds a boolean shape, runs `body` with it as parent, and returns its uuid.

       Forms: (bool attrs & body), (bool label attrs & body).
       A keyword or vector form supplies a label in :refs. Attributes require
       :bool-type :union, :difference, :intersection or :exclude.

       Content and geometry derive from the children. Styles inherit from the
       first child for :difference and the last child otherwise; supplied
       non-nil styles take precedence. A bool requires at least one child and
       accepts paths, rectangles, ellipses, groups and other booleans."
       [label-or-attrs & body]
       (emit-container :bool label-or-attrs body))))

#?(:cljs
   (do


     ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; Attribute generators
     ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

     (defn gen-int
       "Integer generator over `[min, max)`."
       [min max]
       (fn [rng] (rng-int rng min max)))

     (defn gen-float
       "Float generator rounded to three decimals so snapshots stay readable."
       [min max]
       (fn [rng] (rnd/round3 (rng-float rng min max))))

     (defn gen-hex-color
       "Generator of a random `#rrggbb` string."
       []
       (fn [rng] (rng-hex-color rng)))

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
         {:fill-color (rng-hex-color rng)
          :fill-opacity (rng-opacity rng)}))

     (defn gen-stroke
       "Centered stroke like the interim rectangle workload."
       [width]
       (fn [rng]
         {:stroke-width width
          :stroke-alignment :center
          :stroke-color (rng-hex-color rng)
          :stroke-opacity (rng-opacity rng)}))

     (defn gen-vector
       "Composes generators into a generator of a vector, one value per generator."
       [& generators]
       (fn [rng] (mapv #(% rng) generators)))

     ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; Scope state
     ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

     (def ^:dynamic *state*
       "Current scene scope; bound by the `scene` macro."
       nil)

     (def ^:dynamic *defaults*
       "Per-shape-type attribute generator maps bound by the `scene` macro,
        e.g. `{:rect {:x (gen-int 0 1920) ...}}`. Values are literals or one-arg
        functions of the scope random source."
       {})

     (def ^:dynamic *parent*
       "Current insertion point as `{:id parent-id :frame-id containing-frame}`;
  bound by the container scopes, `uuid/zero` at the top."
       {:id uuid/zero :frame-id uuid/zero})

     (defn- check-seed!
       "Requires a scene seed from 0 through 4294967295, retaining the builder
        error type."
       [seed]
       (when-not (rnd/seed? seed)
         (throw (ex-info "scene scope requires an integer :seed in [0, 2^32)"
                         {:type ::invalid-seed
                          :seed seed}))))

     (defn start
       "Starts a scene scope and returns its state atom with objects, refs and :rng.
        The seeded generator belongs to this scope; other scopes and report draws
        cannot advance its state."
       [params]
       (when (some? *state*)
         (throw (ex-info "scene scopes cannot nest"
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
                :rng (rnd/create seed)
                :objects {uuid/zero root}
                :refs {}
                :unfinished {}})))

     (defn- ensure-state
       [state]
       (or state
           (throw (ex-info "shape used outside a scene scope"
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
           (throw (ex-info (str "duplicate scene label: " label)
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

     ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; Shape constructors
     ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

     (defn- normalize-label-and-attrs
       "Resolves map attributes and keyword/vector labels for shape construction."
       [label attrs]
       (cond
         (and (map? label) (empty? attrs)) [nil label]
         (nil? label) [nil attrs]
         (or (keyword? label) (vector? label)) [label attrs]
         :else (throw (ex-info "rect label must be a keyword or vector"
                               {:type ::invalid-label
                                :label label}))))

     (defn- construct-shape
       [state defaults type label attrs]
       (let [state         (ensure-state state)
             [label attrs] (normalize-label-and-attrs label attrs)
             rng           (:rng @state)
             attrs         (resolve-attrs rng (get defaults type) attrs)
             id            (rng-uuid rng)
             shape         (cts/setup-shape (merge attrs
                                                   {:id id
                                                    :type type}))]
         (add-object! state label shape)
         id))

     (defn rect!
       "Adds a rectangle with the selected defaults and attributes, returning its uuid."
       [state defaults label attrs]
       (construct-shape state defaults :rect label attrs))

     (defn path!
       "Adds a path with the selected defaults and attributes, returning its uuid."
       [state defaults label attrs]
       (construct-shape state defaults :path label attrs))

     (defn circle!
       "Adds an ellipse with the selected defaults and attributes, returning its uuid."
       [state defaults label attrs]
       (construct-shape state defaults :circle label attrs))

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
       (reject-attrs! attrs id ::group-geometry
                      "group derives its geometry from children"
                      group-geometry-keys)
       (cts/setup-shape (assoc attrs :id id :type :group)))

     (defn- setup-bool
       [id attrs]
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
               ;; the renderer override, so scene content would depend on renderer
               ;; initialization and A/B configuration. Compose the pure helpers:
               ;; scene generation is untimed and must not vary with the renderer
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

     (defn- check-container-attrs
       [type id attrs]
       (when-not (map? attrs)
         (throw (ex-info (str (name type) " attrs must be a map; the label form takes the attrs map next")
                         {:type ::invalid-attrs :id id})))
       attrs)

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
             attrs (check-container-attrs type id attrs)
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
           (throw (ex-info "scene contains an unfinished container"
                           {:type ::unfinished-container
                            :ids (vec (keys unfinished))})))
         (let [instance {:objects objects
                         :refs refs}]
           (snapshot/validate! instance)
           instance)))))
