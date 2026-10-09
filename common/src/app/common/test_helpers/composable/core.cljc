;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.core
  "The domain-agnostic engine of the composable test model (see
   `mem:frontend/composable-component-tests`): the situation (file, roles,
   vars and the log of applied operations), node identity, strict-presence
   lookup, the `IOperation` and `IEnumerable` protocols, the operators
   `in-sequence`, `one-of`, `optional`, `skip` and `test-that`, and the plain
   runners `run-variant` / `run-all`. Component operations live behind the
   `comp` boundary (`app.common.test-helpers.composable.comp.*`).

   A shape name in an operation is a test label (`app.common.test-helpers.ids-map`)."
  (:refer-clojure :exclude [apply-to])
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [clojure.string :as str]
   [clojure.test :as t]))

;; ---------------------------------------------------------------------------
;; Situation: the in-memory file value with its roles, vars and the log of
;; what was applied to it. Shape labels live in the global label map, which
;; the runners reset per variant.
;; ---------------------------------------------------------------------------

(declare resolve-shape-id)

(defn make-situation
  "A situation for `file`, with `roles` (role key -> shape label) resolved to
   ids at once: the label map changes from variant to variant, so a role keeps
   the id of the file it was built with. `:applied` holds the ids of the
   applied operations in order, `:node-data` what each recorded about its
   application."
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
  "`situation` with its file replaced by `file'`."
  [situation file']
  (assoc situation :file file'))


(defn with-aux-files
  "`situation` with auxiliary files (file id -> file), such as a linked
   library, besides its current file. Roles resolve against the current file;
   an interpreter installs the auxiliary ones next to it."
  [situation files-by-id]
  (assoc situation :aux-files files-by-id))

(defn aux-files
  "The situation's auxiliary files (map of file-id -> file), or an empty map.
   See `with-aux-files`."
  [situation]
  (get situation :aux-files {}))

;; ----- Node identity -------------------------------------------------------
;;
;; Records with equal fields are equal, so every node constructor stamps a
;; unique id under ::id with `assign-id`; what a node records is keyed by it.

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
  "The kebab-case name of a node's record type: a ChangeProperty record
   yields \"change-property\"."
  [node]
  (let [cls   (or #?(:clj  (some-> ^Class (type node) .getSimpleName)
                     :cljs (some-> node type .-name (str/split #"\.") last))
                  "node")
        ;; CamelCase -> kebab-case
        kebab (-> cls
                  (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                  (str/lower-case))]
    kebab))

(defn record-application
  "Record in `situation` that `node` was applied, with `record`, a map of what
   it did, under its identity (read back with `node-data`). The node's kind
   goes in under ::kind for `describe-applied`."
  [situation node record]
  (-> situation
      (update :applied conj (node-id node))
      (assoc-in [:node-data (node-id node)] (assoc record ::kind (node-kind node)))))

(defn node-data
  "What `node` recorded about its application in `situation`, or nil when it
   was not applied."
  [situation node]
  (get-in situation [:node-data (node-id node)]))

(defn applied?
  "Whether this exact `operation` node ran in `situation`, also when it was
   the chosen alternative of a `one-of` or `optional`. Bind an operation to a
   value once and use that value both in the composition and in the query."
  [situation operation]
  (contains? (:node-data situation) (node-id operation)))


(defn- render-application
  "One application record (not a choice) as a short readable line."
  [record]
  (let [kind (::kind record)
        args (dissoc record ::kind)]
    (str kind (when (seq args) (str " " (pr-str args))))))

(defn describe-application
  "A one-line description of what `node` recorded about its application in
   `situation` (see `describe-applied`), or nil if it has not been applied."
  [situation node]
  (some-> (node-data situation node) render-application))

(defn describe-applied
  "The transcript of the operations that produced `situation`, one line per
   application in order, including the branch each one-of chose. Failures
   are labelled with it."
  [situation]
  (let [node-data (:node-data situation)]
    (->> (:applied situation)
         (keep (fn [id]
                 (let [record (get node-data id)]
                   (if (contains? record :chosen)
                     ;; a choice gets a line only when the chosen node has none
                     ;; of its own, such as a skipped `optional`
                     (let [chosen (:chosen record)]
                       (when-not (contains? node-data (::id chosen))
                         (str "one-of -> " (render-application {::kind (node-kind chosen)}))))
                     (render-application record)))))
         (str/join "\n  "))))

;; ----- Role accessors ------------------------------------------------------

(defn role-shape
  "The live shape of role `role-key` in the situation's current page. Throws
   when the role is not bound or its shape is not in the page."
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
  "The live shape with `id` in the situation's current page, or nil."
  [situation id]
  (ctst/get-shape (thf/current-page (file situation)) id))

(defn find-shape
  "The live shape with `id` in any page of the situation's file (nil if
   absent). For shapes outside the current page."
  [situation id]
  (some #(ctst/get-shape % id) (ctpl/pages-seq (:data (file situation)))))

(defn role-shape-in-file
  "Like `role-shape`, but finds the role's shape in any page of the file."
  [situation role-key]
  (let [id (get-in situation [:roles role-key])]
    (when (nil? id)
      (throw (ex-info (str "Unbound role: " (pr-str role-key))
                      {:type ::unbound-role
                       :role role-key})))
    (or (find-shape situation id)
        (throw (ex-info (str "Role " (pr-str role-key) " resolves to a shape no longer "
                             "present in the file (id " (pr-str id) ").")
                        {:type ::role-shape-absent
                         :role role-key
                         :id id})))))


;; ---------------------------------------------------------------------------
;; Situation vars: named values that are not shapes, such as a component id
;; or a nesting count.
;; ---------------------------------------------------------------------------

(defn set-var
  "`situation` with var `k` set to `v`."
  [situation k v]
  (assoc-in situation [:vars k] v))

(defn get-var
  "The value of var `k` in `situation`, or `default`."
  ([situation k] (get-var situation k nil))
  ([situation k default] (get-in situation [:vars k] default)))

(defn target-shape-id
  "The shape id an operation's `target` names, resolved when the operation
   applies: a `(situation -> id)` fn is called, a bound role gives its shape,
   anything else is a label (strict presence)."
  [situation target]
  (cond
    (fn? target)                 (target situation)
    (has-role? situation target) (:id (role-shape situation target))
    :else                        (resolve-shape-id situation target)))

;; ---------------------------------------------------------------------------
;; Strict-presence lookup: an absent label throws, naming the labels present,
;; so a case never asserts against nil.
;; ---------------------------------------------------------------------------

(defn resolve-shape
  "The shape labelled `name` in the situation's current page. Throws, listing
   the labels present, when there is none."
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
  "The id of the shape labelled `name` (see `resolve-shape`)."
  [situation name]
  (:id (resolve-shape situation name)))

;; ---------------------------------------------------------------------------
;; Operation protocol: a step of a composition, as a record so it prints.
;; `apply-to` returns the situation after it, recording its application.
;; ---------------------------------------------------------------------------

(defprotocol IOperation
  (apply-to [operation situation]
    "`situation` after this operation."))

;; ---------------------------------------------------------------------------
;; Enumeration: a composed operation stands for one concrete operation per
;; combination of its choices. Only the operators implement `IEnumerable`; any
;; other operation enumerates to itself.
;; ---------------------------------------------------------------------------

(defprotocol IEnumerable
  (-enumerate [operation]
    "The concrete operations, with no choice left, this one stands for."))

(defn enumerate
  "The concrete operations `operation` stands for."
  [operation]
  (if (satisfies? IEnumerable operation)
    (-enumerate operation)
    [operation]))

;; ---------------------------------------------------------------------------
;; Sequence: applies its steps in order, threading the situation. It
;; enumerates to the cartesian product of its steps' variants, which is what
;; multiplies a case out into a matrix. (`sequence` is a core name, hence
;; `in-sequence`.)
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
    (->> steps
         (map enumerate)
         (reduce (fn [acc step-variants]
                   (for [combo   acc
                         variant step-variants]
                     (conj combo variant)))
                 [[]])
         (map ->Sequence))))

(defn in-sequence
  "Constructor for the sequence operator: apply `steps` in order."
  [steps]
  (->Sequence (vec steps)))

;; ---------------------------------------------------------------------------
;; One-of: one case per alternative. It cannot be applied, only enumerated:
;; each variant is a `RecordedChoice` that records which alternative ran.
;; ---------------------------------------------------------------------------

(declare record-choice)

(defrecord RecordedChoice [one-of-id chosen]
  ;; A variant of a one-of: records the choice under the one-of's identity
  ;; (see `get-choice`), then applies the chosen operation.
  IOperation
  (apply-to [this situation]
    (->> (record-choice situation this)
         (apply-to chosen)))

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
    (for [alt     alternatives
          variant (enumerate alt)]
      (->RecordedChoice (node-id this) variant))))

(defn one-of
  "Constructor for the one-of operator: one case per alternative of
   `alternatives`. Ask it which one ran with `get-choice`."
  [alternatives]
  (assign-id (->OneOf (vec alternatives))))

(defrecord Skip []
  IOperation
  (apply-to [_ situation] situation)
  IEnumerable
  (-enumerate [self] [self]))

(defn skip
  "The operation that changes nothing."
  []
  (->Skip))

(defrecord Test [assert-fn]
  ;; Runs assertions at its point of the sequence and changes nothing.
  IOperation
  (apply-to [this situation]
    (assert-fn situation)
    (record-application situation this {}))
  IEnumerable
  (-enumerate [self] [self]))

(defn test-that
  "Constructor for an inline assertion: `assert-fn` (situation -> any) runs
   its assertions at this point of the sequence."
  [assert-fn]
  (assign-id (->Test assert-fn)))

(defn optional
  "`(one-of [t (skip)])`: the case with and without `t`."
  [t]
  (one-of [t (skip)]))

(defn get-choice
  "The alternative `one-of` chose in `situation`, or nil when it did not run."
  [situation one-of]
  (:chosen (node-data situation one-of)))

;; ---------------------------------------------------------------------------
;; Plain runners: apply operations only, with no sync, undo or judgment. The
;; component cases run through `comp.runner` or the frontend interpreter.
;; ---------------------------------------------------------------------------

(defn run-variant
  "The situation after applying the concrete `operation` to a fresh one from
   `setup`."
  [{:keys [setup operation]}]
  (->> (setup)
       (apply-to operation)))

(defn run-all
  "The resulting situations of every variant of `operation`, in order, each
   run by `run-variant-fn` (default `run-variant`) on `{:setup :operation}`
   with a fresh label map."
  ([case-map] (run-all case-map run-variant))
  ([{:keys [setup operation]} run-variant-fn]
   (->> (enumerate operation)
        (mapv (fn [variant]
                (thi/reset-idmap!)
                (run-variant-fn {:setup setup
                                 :operation variant}))))))

(defn sequence-ops
  "The steps of the concrete `variant`, in order, with sequences flattened. A
   `RecordedChoice` stays one step, so an interpreter can record the choice
   (`record-choice`) and run the chosen operation (`choice-of`)."
  [variant]
  (cond
    (instance? Sequence variant)
    (vec (mapcat sequence-ops (:steps variant)))

    :else
    [variant]))

(defn sequence?
  "Whether `op` is a `Sequence`; its steps are under `:steps`."
  [op]
  (instance? Sequence op))

(defn recorded-choice?
  "Whether `op` is a `RecordedChoice`, a variant of a one-of."
  [op]
  (instance? RecordedChoice op))

(defn choice-of
  "The concrete operation a `RecordedChoice` selected."
  [recorded-choice]
  (:chosen recorded-choice))

(defn choice-one-of-id
  "The identity of the one-of a `RecordedChoice` comes from."
  [recorded-choice]
  (:one-of-id recorded-choice))

(defn record-choice
  "Record in `situation` the alternative `recorded-choice` selected, under its
   one-of's identity, for `get-choice`."
  [situation recorded-choice]
  (-> situation
      (assoc-in [:node-data (choice-one-of-id recorded-choice)]
                {:chosen (choice-of recorded-choice)})
      (update :applied conj (choice-one-of-id recorded-choice))))

;; ---------------------------------------------------------------------------
;; Known failures: an assertion a known bug breaks pins the wrong value the
;; bug gives, so the case fails when the failure changes or stops.
;; ---------------------------------------------------------------------------

(defn known-failure
  "Assert a strict expected failure for `bug` (its row in the plan's suspected
   bugs): `expected` is the value that should hold and `actual` the value
   found. While the bug is open, `actual` must be exactly `pinned`, the wrong
   value the bug gives; any other value fails, `expected` included."
  [bug expected pinned actual]
  (t/is (= pinned actual)
        (str "Known failure " bug ": expected " (pr-str expected)
             ", pinned to the bug's " (pr-str pinned)
             (when (= expected actual)
               ". The expected value now holds: drop the pin in the fix commit"))))
