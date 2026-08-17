;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.composable-tests.core
  "The domain-agnostic ENGINE of the composable test model (see
   `mem:frontend/composable-component-tests`):

     - a `situation` carrying the in-memory file value, named ROLE bindings,
       a `:vars` map, and an ordered record of applied operations,
     - node identity (`assign-id`) + the applied-log (`record-application`,
       `applied?`, `describe-applied`),
     - strict-presence lookup that throws a diagnostic error on a missing name,
     - the `IOperation` protocol (single method `apply-to`) and the
       `IEnumerable` protocol behind variant enumeration,
     - the composition operators `in-sequence`, `one-of`, `optional`, `skip`,
       and the inline-assertion operation `test-that`,
     - the enumerating pure runners `run-variant` / `run-all` (the frontend
       interpreter in `frontend-tests.composable-tests.interpreter` is the
       event-dispatching counterpart and the test-facing entry point).

   No Penpot domain terms live here; the component-specific operations sit
   behind the `comp` boundary (`frontend-tests.composable-tests.comp.*`).

   Shape references use the existing label->uuid system (`app.common.test-helpers.ids-map`)
   rather than a parallel binding map: a binding name in an operation IS a
   shape label, resolved through `thi/id`. The situation adds the applied-log and
   the strict-presence discipline on top of that substrate."
  (:refer-clojure :exclude [apply-to])
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Situation
;;
;; A situation wraps the in-memory Penpot file value together with the record of
;; what has been applied to it. The environment of *shape* bindings is the
;; global label->uuid map (reset per variant by the runners), so the situation
;; does not duplicate it; it only adds:
;;   :file        - the current in-memory file value
;;   :applied-log - vector of application records, in application order
;; ---------------------------------------------------------------------------

;; resolve-shape is defined below (strict-presence section) but referenced by
;; role-shape above it; declare it so the forward reference resolves at load.
(declare resolve-shape)
(declare resolve-shape-id)

(defn make-situation
  "Create a situation from an initial in-memory file value, optionally with role
   bindings. Roles are meaningful named objects of the configuration the setup
   built (e.g. :main-instance, :copy-instance), passed as shape *labels*. The
   labels are resolved to ids IMMEDIATELY (at construction, while the labels are
   freshly valid) and the situation stores the resolved ids. This is essential:
   the global label->id map is time-varying and shared, so resolving a role label
   later (e.g. in another enumerated variant's run) would be unsound; capturing
   the id at setup time makes each situation resolve its roles against the file it
   was built with, independent of the global map's later state.

   The situation also carries:
     :applied   - ordered vector of operation ids, in application order
                  (so the full sequence is always recoverable from the situation)
     :node-data - map of operation-id -> the record that operation
                  wrote about its own application (keyed by NODE IDENTITY, not by
                  a node-kind tag), so each node retrieves its own record."
  ([file] (make-situation file {}))
  ([file roles]
   {:file file
    ;; resolve role labels to ids now, while the labels are valid
    :roles (into {} (map (fn [[k label]] [k (thi/id label)])) roles)
    :applied []
    :node-data {}}))

(defn file
  "The current in-memory file value of the situation."
  [situation]
  (:file situation))

(defn with-file
  "Return a situation with its file value replaced (the threaded result of
   applying a transformation through the production change pipeline)."
  [situation file']
  (assoc situation :file file'))


(defn with-aux-files
  "Return a situation carrying AUXILIARY files (a map of file-id -> file value)
   beyond the primary `:file`. Used by cross-file configurations (e.g. a copy in
   the primary/current file whose main lives in a linked LIBRARY file): the
   primary `:file` stays the current file the role accessors resolve against,
   while the auxiliary files travel alongside so an interpreter can install them
   too (e.g. into the frontend store's `:files`, so cross-file sync can run).
   The primary file is NOT included here."
  [situation files-by-id]
  (assoc situation :aux-files files-by-id))

(defn aux-files
  "The situation's auxiliary files (map of file-id -> file), or an empty map.
   See `with-aux-files`."
  [situation]
  (get situation :aux-files {}))

;; ----- Node identity (central, no inheritance) -----------------------------
;;
;; Records are value-equal in Clojure, so two structurally-identical nodes would
;; collide as map keys. Each node therefore carries an explicit unique id under
;; the reserved key ::id, stamped at construction by `assign-id` (the single
;; thing every node constructor must call). The id machinery — stamping, and the
;; record/retrieve-by-id pair below — is implemented ONCE here and shared by all
;; nodes as plain functions; nodes never reimplement it, they just pass `this`.

(defn assign-id
  "Stamp a fresh unique id onto a node record (reserved key ::id). Every node
   constructor's final step. Returns the node."
  [node]
  (assoc node ::id (uuid/next)))

(defn node-id
  "The unique id of a node."
  [node]
  (::id node))

;; ----- Self-description, keyed by node identity ----------------------------

(defn- node-kind
  "A short readable kind name for a node, derived from its record type (no
   per-node boilerplate, faithful to the actual type). E.g. a ChangeAttr record
   yields \"change-attr\"."
  [node]
  (let [cls   (or (some-> node type .-name (str/split #"\.") last)
                  "node")
        ;; CamelCase -> kebab-case
        kebab (-> cls
                  (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                  (str/lower-case))]
    kebab))

(defn record-application
  "Record what an operation `node` did into `situation`, keyed by the node's
   identity, and append its id to the ordered :applied sequence. `record` is an
   arbitrary map describing the application; the node retrieves it later via
   `node-data` by passing itself. The node's kind (from its type) is merged in
   under ::kind so the application can be rendered (see `describe-applied`)
   without the node needing to name itself. Both consumers (inspection methods,
   undo) read through node identity and ignore the extra ::kind key."
  [situation node record]
  (-> situation
      (update :applied conj (node-id node))
      (assoc-in [:node-data (node-id node)] (assoc record ::kind (node-kind node)))))

(defn node-data
  "The record that `node` wrote about its own application in `situation`, or nil
   if it has not been applied. Keyed by node identity."
  [situation node]
  (get-in situation [:node-data (node-id node)]))

(defn applied-ids
  "The ordered vector of operation ids applied to the situation (the full
   sequence, recoverable from the situation)."
  [situation]
  (:applied situation))

(defn applied?
  "Whether `operation` ran in this situation. Identity-based: true iff this exact
   operation node (by its `::id`) recorded an application. Works through `optional`
   / `one-of`: a chosen alternative records itself under its own identity, so
   querying with the raw operation answers correctly. Bind an operation to a value
   ONCE and reuse it (in the composition AND in any `Test` that queries it), so the
   id you ask about is the id that ran."
  [situation operation]
  (contains? (:node-data situation) (node-id operation)))


(defn- render-application
  "Render one (non-choice) application record as a short human-readable string.
   Content-agnostic: uses ::kind and the record's own fields."
  [record]
  (let [kind (::kind record)
        args (dissoc record ::kind)]
    (str kind (when (seq args) (str " " (pr-str args))))))

(defn describe-applied
  "A human-readable, ordered transcript of the operations that produced
   `situation` — one line per application, in order. Built purely from the
   situation's recorded :applied ids and :node-data records (each node's own
   self-description), so it is faithful to what actually ran, including the branch
   a one-of chose. Used to label failures with the exact sequence at hand."
  [situation]
  (let [node-data (:node-data situation)]
    (->> (:applied situation)
         (keep (fn [id]
                 (let [record (get node-data id)]
                   (if (contains? record :chosen)
                     ;; a one-of's choice record: if the chosen node recorded its
                     ;; own application, its own line (also in :applied) already
                     ;; tells the story — emit nothing to avoid a duplicate line.
                     ;; Only a non-recording choice (a skipped `optional`) is
                     ;; rendered here, so a skip stays visible in the transcript.
                     (let [chosen (:chosen record)]
                       (when-not (contains? node-data (::id chosen))
                         (str "one-of -> " (render-application {::kind (node-kind chosen)}))))
                     (render-application record)))))
         (str/join "\n  "))))

;; ----- Role accessors ------------------------------------------------------
;;
;; A setup records role bindings (meaningful named objects of the configuration)
;; as shape labels. `role-shape` resolves a role to the live shape in the current
;; file, with strict presence: an absent role throws diagnostically (never nil).
;; Specific named accessors (e.g. copy-instance) are thin wrappers, so tests read
;; `(copy-instance situation)` rather than knowing the role key.

(defn role-shape
  "Resolve role `role-key` to the live shape in the situation's current file,
   using the id captured at setup time. Throws a diagnostic error if the role is
   not bound, or if its captured shape is no longer present in the current page
   (e.g. it was deleted) — never returns nil silently."
  [situation role-key]
  (let [id   (get-in situation [:roles role-key])
        page (thf/current-page (file situation))]
    (when (nil? id)
      (throw (ex-info (str "Unbound role: " (pr-str role-key)
                           ". Roles present: " (pr-str (vec (keys (:roles situation)))))
                      {:type ::unbound-role
                       :role role-key})))
    (let [shape (ctst/get-shape page id)]
      (when (nil? shape)
        (throw (ex-info (str "Role " (pr-str role-key) " resolves to a shape no longer "
                             "present in the current page (id " (pr-str id) ").")
                        {:type ::role-shape-absent
                         :role role-key
                         :id id})))
      shape)))


(defn has-role?
  "Whether `role-key` is currently bound in the situation."
  [situation role-key]
  (contains? (:roles situation) role-key))

(defn shape-by-id
  "The live shape with `id` in the situation's current page (nil if absent). For
   reading a shape whose id is held outside the role map — e.g. a field of a
   tracked component object."
  [situation id]
  (ctst/get-shape (thf/current-page (file situation)) id))

(defn rebind-role
  "Re-point `role-key` to the current id of shape `label` (resolved via the global
   label map now, while it is valid). Used by state-building operations that move
   the configuration (e.g. `make-nested-component`) and must update where a role points so
   that role-targeted operations keep acting on the intended shape."
  [situation role-key label]
  (assoc-in situation [:roles role-key] (thi/id label)))

(defn rebind-role-id
  "Like `rebind-role`, but re-points `role-key` to a shape `id` directly (when the
   operation discovered the shape by id, e.g. by following a :shape-ref chain,
   rather than by label)."
  [situation role-key id]
  (assoc-in situation [:roles role-key] id))


;; ---------------------------------------------------------------------------
;; Situation vars — arbitrary named values beyond roles
;;
;; Roles name SHAPES (resolved from labels to ids). Some operations need to track
;; named values that are NOT shapes — e.g. a component id to instantiate next, or
;; a counter (nesting depth). Those live in a separate `:vars` map so they do not
;; get confused with shape roles. Set/read with `set-var`/`get-var`.
;; ---------------------------------------------------------------------------

(defn set-var
  "Associate `k` with `v` in the situation's `:vars` (arbitrary named values that
   are NOT shapes — e.g. a component id, a counter). Returns the situation."
  [situation k v]
  (assoc-in situation [:vars k] v))

(defn get-var
  "Read the value of `k` from the situation's `:vars`, or `default` (nil if
   omitted) when absent. See `set-var`."
  ([situation k] (get-var situation k nil))
  ([situation k default] (get-in situation [:vars k] default)))

(defn target-shape-id
  "Resolve an operation's target to a shape id:
     - a FUNCTION `(situation -> id)` is called with the situation (lets the target
       be computed from situation state — e.g. a field of a tracked object —
       resolved at apply-time so it follows state changes);
     - a currently-bound ROLE resolves via the role (so the operation follows the
       role as state-building ops re-point it);
     - otherwise `target` is a label (strict-presence).
   This is what makes a single operation composable across depth / with
   make-nested-component et al."
  [situation target]
  (cond
    (fn? target)                 (target situation)
    (has-role? situation target) (:id (role-shape situation target))
    :else                        (resolve-shape-id situation target)))

;; ---------------------------------------------------------------------------
;; Strict-presence lookup
;;
;; Resolving a binding name (shape label) that is absent must throw a DIAGNOSTIC
;; error: it names the absent binding and lists those present. A silently-nil
;; lookup is forbidden (it would make a declarative case a lie / a green test
;; that asserts against nothing). Acting on a present-but-wrong-kind binding is
;; left to crash naturally downstream.
;; ---------------------------------------------------------------------------

(defn resolve-shape
  "Resolve a shape binding name (label) to the actual shape in the situation's
   current page, throwing a diagnostic error if the name is not bound to a shape
   that exists in that page. The diagnostic lists the labels of the shapes that
   ARE present, derived from the file itself (not from a parallel binding map)."
  [situation name]
  (let [the-file (file situation)
        page     (thf/current-page the-file)
        id       (thi/id name)
        shape    (when id (ctst/get-shape page id))]
    (when (nil? shape)
      (let [present (->> (vals (:objects page))
                         (map (comp thi/label :id))
                         (sort)
                         (vec))]
        (throw (ex-info (str "Unbound shape name: " (pr-str name)
                             ". Shapes present in current page: " (pr-str present))
                        {:type ::unbound-shape-name
                         :name name
                         :present present}))))
    shape))

(defn resolve-shape-id
  "Resolve a shape binding name (label) to its uuid, with strict-presence
   diagnostics (see `resolve-shape`)."
  [situation name]
  (:id (resolve-shape situation name)))

;; ---------------------------------------------------------------------------
;; Operation protocol
;;
;; An OPERATION is a step in a composition. Most operations TRANSFORM the
;; situation (an edit, a structural change, a nesting), but some do not (a `Test`
;; asserts and returns the situation unchanged; `Skip` is a no-op) — hence the
;; genus is "operation", not "transformation". An operation is reified as DATA (a
;; record), not a bare function, so it is printable and navigable. `apply-to`
;; takes the operation and a situation and returns a (possibly identical)
;; situation; it may also record its own application. The method cannot be named
;; `apply` (core clash).
;; ---------------------------------------------------------------------------

(defprotocol IOperation
  (apply-to [operation situation]
    "Apply this operation to `situation`, returning a (possibly identical)
     situation."))


;; ---------------------------------------------------------------------------
;; Enumeration
;;
;; A composed operation may stand for MANY concrete cases (because `one-of`
;; offers alternatives). Enumeration turns one composed operation into the
;; sequence of fully-concrete operations it represents — each of which has
;; no remaining choice and can be `apply-to`'d directly.
;;
;; Enumeration is a generic-engine concern: only the composition operators
;; (`in-sequence`, `one-of`) implement it. Leaf/subject operations (e.g. the
;; component nodes) need not know about it — the top-level `enumerate` falls back
;; to "an operation with no choice enumerates to itself", so a plain node yields a
;; single variant (itself). This keeps `apply-to` and `enumerate` orthogonal and
;; keeps the `comp` node library free of enumeration code.
;; ---------------------------------------------------------------------------

(defprotocol IEnumerable
  (-enumerate [operation]
    "Return a sequence of fully-concrete operations this one represents."))

(defn enumerate
  "The concrete operations represented by `operation`. Falls back to a single
   variant (the operation itself) for anything that does not implement
   IEnumerable (i.e. leaf operations with no internal choice)."
  [operation]
  (if (satisfies? IEnumerable operation)
    (-enumerate operation)
    [operation]))

;; ---------------------------------------------------------------------------
;; Composition operator: sequence
;;
;; A `Sequence` holds an ordered vector of child operations and applies them
;; left-to-right, THREADING the situation through each: the situation returned
;; by one child is the input to the next. Operations do not commute, so order
;; is explicit. Its enumeration is the CARTESIAN PRODUCT of its children's
;; variants (with single-variant children this is exactly one Sequence), which
;; is what makes a `one-of`/`optional` composed inside a sequence multiply the
;; case out into a matrix.
;;
;; The constructor is named `in-sequence` because `sequence` is a clojure.core
;; name and must not be shadowed.
;; ---------------------------------------------------------------------------

(declare ->Sequence)

(defrecord Sequence [steps]
  IOperation
  (apply-to [_ situation]
    (reduce (fn [sit step] (apply-to step sit))
            situation
            steps))

  IEnumerable
  (-enumerate [_]
    ;; Cartesian product: every combination of one concrete variant per step,
    ;; each combination rewrapped as a concrete Sequence. With single-variant
    ;; steps this yields exactly one Sequence (the all-singletons case).
    (->> steps
         (map enumerate)                 ; step -> seq of concrete variants
         (reduce (fn [acc step-variants]
                   (for [combo   acc
                         variant step-variants]
                     (conj combo variant)))
                 [[]])                    ; seed: one empty combination
         (map ->Sequence))))

(defn in-sequence
  "Constructor for the sequence operator: apply `steps` (a seq of
   transformations) in order, threading the situation through each."
  [steps]
  (->Sequence (vec steps)))


;; ---------------------------------------------------------------------------
;; Composition operator: one-of
;;
;; `one-of` offers a set of alternative transformations: it represents N cases,
;; one per alternative. It is a pure *enumeration* construct — it has no single
;; apply semantics, because applying it would mean arbitrarily picking one
;; alternative. So `apply-to` on a raw OneOf is an error; it must be enumerated
;; first (the runner does this). Its `-enumerate` is the UNION of its
;; alternatives' enumerations, so `one-of` composed inside `in-sequence`
;; multiplies out correctly via the sequence's cartesian product.
;; ---------------------------------------------------------------------------

(defrecord RecordedChoice [one-of-id chosen]
  ;; Internal node produced by OneOf enumeration. Applying it records, under the
  ;; originating one-of's identity, which alternative was chosen, then applies
  ;; that alternative. This is how `get-choice` (read by the test holding the
  ;; one-of) recovers the choice from the resulting situation. It is already
  ;; concrete, so it enumerates to itself.
  IOperation
  (apply-to [_ situation]
    (-> situation
        (assoc-in [:node-data one-of-id] {:chosen chosen})
        (update :applied conj one-of-id)
        (->> (apply-to chosen))))

  IEnumerable
  (-enumerate [this] [this]))

(defrecord OneOf [alternatives]
  IOperation
  (apply-to [_ _]
    (throw (ex-info (str "OneOf cannot be applied directly; it represents a choice "
                         "and must be enumerated first (the runners do this via `enumerate`).")
                    {:type ::one-of-applied-directly
                     :alternative-count (count alternatives)})))

  IEnumerable
  (-enumerate [this]
    ;; Each alternative may itself enumerate to several concrete variants; every
    ;; resulting concrete variant is wrapped so that, when applied, it records
    ;; this one-of's choice under this one-of's identity.
    (for [alt     alternatives
          variant (enumerate alt)]
      (->RecordedChoice (node-id this) variant))))

(defn one-of
  "Constructor for the one-of operator: represents one case per alternative in
   `alternatives`. Used to sweep a set of variants (e.g. several attribute
   changes) across an otherwise-shared case. Carries an identity so the choice it
   made in a given enumerated run can be recovered via `get-choice`."
  [alternatives]
  (assign-id (->OneOf (vec alternatives))))


(defrecord Skip []
  IOperation
  (apply-to [_ situation] situation)
  IEnumerable
  (-enumerate [self] [self]))

(defn skip
  "The identity operation: applies as a no-op, enumerates to itself. The building
   block of `optional`."
  []
  (->Skip))

(defrecord Test [assert-fn]
  ;; An operation that makes ASSERTIONS at this point in the sequence and leaves
  ;; the situation unchanged. `assert-fn` is a (situation -> any) that performs the
  ;; assertions itself (e.g. calls `t/is`), exactly like an external asserter — so
  ;; checks can be placed at INTERMEDIATE steps, not only at the end. It records
  ;; its application (so the transcript shows where checkpoints sit) but changes
  ;; nothing. Concrete, so it enumerates to itself.
  IOperation
  (apply-to [this situation]
    (assert-fn situation)
    (record-application situation this {}))
  IEnumerable
  (-enumerate [self] [self]))

(defn test-that
  "Constructor for an inline `Test` operation. `assert-fn` is a (situation -> any)
   that performs assertions (it is run for side effects; its return is ignored).
   Place it inside an `in-sequence` to assert at that point in the trajectory.
   Typically queries `applied?`/`get-choice`/`has-property-of` to decide what to
   assert for the current enumerated variant."
  [assert-fn]
  (assign-id (->Test assert-fn)))

(defn optional
  "`(optional t)` = `(one-of [t (skip)])`: sweeps WITH and WITHOUT `t` across a
   case (two enumerated variants). Kept as its own named constructor for
   readability. The workhorse for adding a state-building step (e.g.
   `(optional (make-nested-component))`) as an axis over an existing case."
  [t]
  (one-of [t (skip)]))

(defn get-choice
  "The alternative this `one-of` chose in `situation` (the concrete transformation
   that actually ran for this branch), or nil if it did not run in this variant.
   Recovered by node identity, so the test holds the one-of and asks it."
  [situation one-of]
  (:chosen (node-data situation one-of)))

;; ---------------------------------------------------------------------------
;; Executors
;;
;; Build a fresh situation from a setup thunk and apply a (possibly composed)
;; OPERATION. `run-variant` runs one already-concrete variant; `run-all`
;; enumerates a composed operation and runs every variant. Neither makes a
;; judgment or touches clojure.test — `check` (in
;; `frontend-tests.composable-tests.interpreter`) is the test-facing entry point. (Assertions can also live INSIDE the operation, via
;; `Test`; those fire as the operation is applied.)
;; ---------------------------------------------------------------------------

(defn run-variant
  "Run one concrete (already-enumerated) variant: build a fresh situation via
   `setup` (a 0-arg fn returning a *situation*) and apply `operation`,
   returning the resulting situation. Makes no judgment. Exceptions propagate.
   This is the per-variant step `check` builds on; tests use `check`, not this."
  [{:keys [setup operation]}]
  (->> (setup)
       (apply-to operation)))


(defn run-all
  "Enumerate `operation` into its concrete variants and run each (fresh setup
   per variant, isolated label space — the id map is reset before each variant's
   setup so per-variant setups don't clobber labels). Returns a vector of the
   resulting situations, one per enumerated variant, in enumeration order. Makes
   no judgment and does not touch clojure.test — see `check` (in
   `frontend-tests.composable-tests.interpreter`) for the test-facing entry point that asserts."
  [{:keys [setup operation]}]
  (->> (enumerate operation)
       (mapv (fn [variant]
               (thi/reset-idmap!)
               (run-variant {:setup setup
                             :operation variant})))))


(defn sequence-ops
  "Flatten a CONCRETE (already-enumerated) operation into its ordered units.
   Used by alternative interpreters (e.g. the frontend one) that dispatch
   per-operation effects rather than calling `apply-to`.

   A concrete variant is a single operation record, or a `Sequence` of them, with
   `RecordedChoice` (the enumerated form of a one-of) appearing where a one-of was.
   `Sequence` is flattened; a `RecordedChoice` is KEPT as a unit (it carries both
   the chosen operation and the one-of identity, so a per-op interpreter can
   dispatch the chosen op's effect AND record the choice for `get-choice`). Use
   `recorded-choice?`, `choice-of`, and `choice-one-of-id` to handle those units."
  [variant]
  (cond
    (instance? Sequence variant)
    (vec (mapcat sequence-ops (:steps variant)))

    :else
    [variant]))

(defn recorded-choice?
  "True if `op` is a one-of's enumerated choice wrapper."
  [op]
  (instance? RecordedChoice op))

(defn choice-of
  "The concrete operation a `RecordedChoice` selected."
  [recorded-choice]
  (:chosen recorded-choice))

(defn choice-one-of-id
  "The identity of the one-of that produced this `RecordedChoice` (so a per-op
   interpreter can record the choice under it, enabling `get-choice`)."
  [recorded-choice]
  (:one-of-id recorded-choice))
