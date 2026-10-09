;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.undo-check
  "The undo/redo round trip that both composable runners run on every case
   variant. With S(k) the normalized file after step k:

     - after a user step k that adds an undo entry, undo must give back
       S(k-1) and redo S(k); the variant then goes on from the redone file;
     - a user step that adds no undo entry must change nothing;
     - at the end of the variant, undoing back to the last assembly step must
       give back the file after it, and redoing all again the final file.

   Assembly steps do not go through the undo stack, so the check never undoes
   past the last one. Inline `Test` operations are not run again.

   The runners record each failed comparison in the situation, and `check!`
   asserts on them after the case's own asserter. The case map's
   `:undo-check` may hold:
     - `:off`, the reason the case turns the check off;
     - `:known-failures`, the failures a known bug causes, each a map of
       `:bug` (its row in the plan's suspected bugs), `:phases` (a set),
       `:op` (the case's operation node whose step fails, matched by node
       identity; any step when nil), `:runners` (a subset of `#{:pure
       :frontend}`; both when nil) and `:when` (a situation predicate).
       A known failure applies to a variant of its runners where its `:op`
       ran and `:when` holds. One that applies but does not happen fails the
       case, so the mark goes once the bug is fixed."
  (:require
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [clojure.test :as t]
   [clojure.walk :as walk]))

(defn with-runner
  "Record in `situation` which runner runs it, `:pure` or `:frontend`, for
   the `:runners` of known failures."
  [situation runner]
  (assoc situation ::runner runner))

(defn enabled?
  "Whether `case-map` runs the round trip."
  [case-map]
  (not (contains? (:undo-check case-map) :off)))

(defn snapshot
  "The normalized value of `file` that the round trip compares."
  ([file] (snapshot file {}))
  ([file opts] (thn/normalize-file file opts)))

(defn mark-baseline
  "Record the state the end-of-variant round trip undoes back to: the
   normalized file `snap` with `depth` undo groups on the stack."
  [situation depth snap]
  (assoc situation ::baseline {:depth depth :snapshot snap}))

(defn baseline
  "The state recorded by `mark-baseline`, as `{:depth :snapshot}`."
  [situation]
  (::baseline situation))

(defn step
  "The step of operation `op` in `situation`, as failures name it: the node
   `:id` and its `:description` in the transcript."
  [situation op]
  {:id          (tm/node-id op)
   :description (tm/describe-application situation op)})

(defn add-failure
  "Record a failed check in `situation`. `failure` holds the `:phase` (see
   `compare-states`), the `:step` (see `step`; nil for the end-of-variant
   phases) and what went wrong (`:diff` or `:error`)."
  [situation failure]
  (update situation ::failures (fnil conj []) failure))

(defn- labelled
  "`value` with every uuid replaced by its test label. Labels are valid only
   while the variant that registered them runs."
  [value]
  (walk/postwalk #(if (uuid? %) (thi/label %) %) value))

(defn compare-states
  "Record a failure of `phase` at `step` unless the normalized files
   `expected` and `actual` are equal. Phases: `:undo`, `:redo`,
   `:no-undo-entry` (a step that added no entry changed the file),
   `:variant-undo` and `:variant-redo`."
  [situation step phase expected actual]
  (if (= expected actual)
    situation
    (add-failure situation {:phase phase
                            :step step
                            :diff (labelled (thn/diff expected actual))})))

(defn failures
  "The failed checks recorded in `situation`, in order."
  [situation]
  (get situation ::failures []))

(defn- matches?
  [{:keys [phases op]} failure]
  (and (contains? phases (:phase failure))
       (or (nil? op)
           (nil? (:step failure))
           (= (tm/node-id op) (get-in failure [:step :id])))))

(defn verdict
  "The failures of `situation`, a variant of `case-map`, that no known failure
   of the case explains (`:unexpected`), and the known failures that apply to
   the variant but did not happen (`:resolved`)."
  [situation case-map]
  (let [applies? (fn [{:keys [op runners] pred :when}]
                   (and (or (nil? runners) (contains? runners (::runner situation)))
                        (or (nil? op) (tm/applied? situation op))
                        (or (nil? pred) (pred situation))))
        known    (filter applies? (get-in case-map [:undo-check :known-failures]))
        found    (failures situation)]
    {:unexpected (filterv (fn [failure] (not-any? #(matches? % failure) known)) found)
     :resolved   (filterv (fn [k] (not-any? #(matches? k %) found)) known)}))

(defn- describe-failure
  [{:keys [phase step diff error]}]
  (str "Undo check failed: " (name phase)
       (when step (str " of step " (:description step)))
       (if error
         (str "; error: " error)
         (str "\n  expected only: " (pr-str (first diff))
              "\n  actual only:   " (pr-str (second diff))))))

(defn check!
  "Assert that the round trip of `situation`, a variant of `case-map`, found
   no failure but the case's known ones, and that each known one happened."
  [situation case-map]
  (when (enabled? case-map)
    (let [{:keys [unexpected resolved]} (verdict situation case-map)]
      (doseq [failure unexpected]
        (t/is false (describe-failure failure)))
      (doseq [{:keys [bug phases op]} resolved]
        (t/is false (str "Known undo failure " bug " " (pr-str phases)
                         (when op (str " of step " (pr-str (into {} op))))
                         " no longer happens: remove its mark from the case"))))))
