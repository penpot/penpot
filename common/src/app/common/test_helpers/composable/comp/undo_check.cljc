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

   The runners record each failed check in the situation, with its
   `:signature`, the set of what differs (see `signature`), and `check!`
   asserts on them after the case's own asserter. The case map's
   `:undo-check` may hold:
     - `:off`, the reason the case turns the check off;
     - `:known-failures`, the failures a known bug causes: strict expected
       failures that pin how the check fails. Each is a map of `:bug` (its
       row in the plan's suspected bugs), `:fails` (phase -> the exact
       signature the failure of that phase has), `:op` (the case's operation
       node whose step fails, matched by node identity; any step when nil),
       `:runners` (a subset of `#{:pure :frontend}`; both when nil) and
       `:when` (a situation predicate).
       A mark applies to a variant of its runners where its `:op` ran and
       `:when` holds. A failure it does not pin exactly stays a failure, and
       a pinned phase that does not fail fails the case, so the mark goes
       only with the fix."
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

(defn- add-failure
  [situation failure]
  (update situation ::failures (fnil conj []) failure))

(defn add-error
  "Record that the round trip could not run `phase` at `step` (see `step`;
   nil for the end-of-variant phases): `error` says why."
  [situation step phase error]
  (add-failure situation {:phase     phase
                          :step      step
                          :signature #{[:error]}
                          :error     error}))

(defn- labelled
  "`value` with every uuid replaced by its test label. Labels are valid only
   while the variant that registered them runs."
  [value]
  (walk/postwalk #(if (uuid? %) (thi/label %) %) value))

(defn- label-of
  "The test label of `id`, or `:unlabelled`, so a signature is the same in
   every run."
  [id]
  (let [label (thi/label id)]
    (if (keyword? label) label :unlabelled)))

(defn- differing-keys
  "The keys, other than those in `skip`, whose values differ between the maps
   `expected` and `actual`."
  [expected actual skip]
  (->> (into (set (keys expected)) (keys actual))
       (remove skip)
       (filter #(not= (get expected %) (get actual %)))))

(defn- entities-signature
  "What differs between the maps of entities by id `expected` and `actual`:
   `[kind label attr]` for an attribute, `[kind label :missing]` or
   `[kind label :extra]` for an entity on one side only. `inner` gives the
   entries of what differs inside an entity present on both sides, for the
   attributes it skips."
  [kind expected actual skip inner]
  (into #{}
        (mapcat (fn [id]
                  (let [e     (get expected id)
                        a     (get actual id)
                        label (label-of id)]
                    (cond
                      (= e a)   nil
                      (nil? a)  [[kind label :missing]]
                      (nil? e)  [[kind label :extra]]
                      :else     (concat (for [attr (differing-keys e a skip)]
                                          [kind label attr])
                                        (inner e a))))))
        (into (set (keys expected)) (keys actual))))

(defn signature
  "What differs between the normalized files `expected` and `actual`, as a
   set of entries: `[:object label attr]` for a shape attribute (or `:missing`
   / `:extra` for a whole shape), the same with `:page` and `:component`, and
   `[:data key]` / `[:file key]` for anything else. Shapes without a test
   label are `:unlabelled`."
  [expected actual]
  (let [ed (:data expected)
        ad (:data actual)]
    (-> #{}
        (into (map #(vector :file %)) (differing-keys expected actual #{:data}))
        (into (map #(vector :data %)) (differing-keys ed ad #{:pages-index :components}))
        (into (entities-signature :page (:pages-index ed) (:pages-index ad) #{:objects}
                                  (fn [e a]
                                    (entities-signature :object (:objects e) (:objects a)
                                                        #{} (constantly nil)))))
        (into (entities-signature :component (:components ed) (:components ad)
                                  #{} (constantly nil))))))

(defn compare-states
  "Record a failure of `phase` at `step` unless the normalized files
   `expected` and `actual` are equal. Phases: `:undo`, `:redo`,
   `:no-undo-entry` (a step that added no entry changed the file),
   `:variant-undo` and `:variant-redo`."
  [situation step phase expected actual]
  (if (= expected actual)
    situation
    (add-failure situation {:phase     phase
                            :step      step
                            :signature (signature expected actual)
                            :diff      (labelled (thn/diff expected actual))})))

(defn compare-index
  "Record a failure of `phase` at `step` unless the workspace undo index is
   `expected`: its signature is `#{[:undo-index n]}`, with `n` the entries
   the index is off by. Phases: `:undo-index` and `:redo-index`."
  [situation step phase expected actual]
  (if (= expected actual)
    situation
    (add-failure situation {:phase     phase
                            :step      step
                            :signature #{[:undo-index (- actual expected)]}
                            :error     (str "undo index " actual ", expected " expected)})))

(defn failures
  "The failed checks recorded in `situation`, in order."
  [situation]
  (get situation ::failures []))

(defn- matches?
  "Whether the known failure `mark` pins `failure` exactly."
  [{:keys [fails op]} failure]
  (and (= (get fails (:phase failure)) (:signature failure))
       (or (nil? op)
           (nil? (:step failure))
           (= (tm/node-id op) (get-in failure [:step :id])))))

(defn- check-mark!
  [{:keys [bug fails] :as mark}]
  (when-not (and (map? fails) (seq fails))
    (throw (ex-info (str "Known undo failure " bug " pins no failure: give it "
                         "`:fails`, phase -> signature")
                    {:type ::unpinned-mark
                     :mark mark}))))

(defn verdict
  "The failures of `situation`, a variant of `case-map`, that no known failure
   of the case pins (`:unexpected`), and the pinned phases of the known
   failures that apply to the variant but did not fail as pinned
   (`:resolved`, each the mark with its `:phase`)."
  [situation case-map]
  (let [marks    (get-in case-map [:undo-check :known-failures])
        _        (run! check-mark! marks)
        applies? (fn [{:keys [op runners] pred :when}]
                   (and (or (nil? runners) (contains? runners (::runner situation)))
                        (or (nil? op) (tm/applied? situation op))
                        (or (nil? pred) (pred situation))))
        known    (filter applies? marks)
        found    (failures situation)]
    {:unexpected (filterv (fn [failure] (not-any? #(matches? % failure) known)) found)
     :resolved   (vec (for [mark  known
                            phase (keys (:fails mark))
                            :when (not-any? #(and (= phase (:phase %)) (matches? mark %))
                                            found)]
                        (assoc mark :phase phase)))}))

(defn- describe-failure
  [{:keys [phase step signature diff error]}]
  (str "Undo check failed: " (name phase)
       (when step (str " of step " (:description step)))
       "\n  signature: " (pr-str signature)
       (if error
         (str "\n  error: " error)
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
      (doseq [{:keys [bug phase fails op]} resolved]
        (t/is false (str "Known undo failure " bug " did not fail as pinned: "
                         (name phase) " " (pr-str (get fails phase))
                         (when op (str " of step " (pr-str (dissoc (into {} op) ::tm/id))))
                         ". If the fix landed, remove the mark in the fix commit; "
                         "otherwise the failure changed: see the failures above"))))))
