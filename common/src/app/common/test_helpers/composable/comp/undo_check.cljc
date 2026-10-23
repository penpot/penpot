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
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
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

(defn snapshot-files
  "The normalized files affected by a step, including linked libraries."
  ([files] (snapshot-files files {}))
  ([files opts]
   {:files (into {} (map (fn [[id file]] [id (snapshot file opts)])) files)}))

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
   nil for the end-of-variant phases): `error` says why and `kind`, when
   given, names the error in the signature (the `:type` of its ex-data)."
  ([situation step phase error]
   (add-error situation step phase error nil))
  ([situation step phase error kind]
   (add-failure situation {:phase     phase
                           :step      step
                           :signature #{(cond-> [:error] kind (conj kind))}
                           :error     error})))

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
   `expected` and `actual`, or that only one of them holds (a nil value
   differs from an absent key)."
  [expected actual skip]
  (->> (into (set (keys expected)) (keys actual))
       (remove skip)
       (filter #(or (not= (contains? expected %) (contains? actual %))
                    (not= (get expected %) (get actual %))))))

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
  (if (or (contains? expected :files) (contains? actual :files))
    (into #{}
          (mapcat (fn [id]
                    (map #(into [:file (label-of id)] %)
                         (signature (get-in expected [:files id])
                                    (get-in actual [:files id])))))
          (into (set (keys (:files expected))) (keys (:files actual))))
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
                                    #{} (constantly nil)))))))

(defn- same-state?
  "Reflow can recompute geometry at machine precision after undo. Keep
   snapshots exact, allowing only absolute geometry noise below 1e-10."
  ([expected actual] (same-state? expected actual false))
  ([expected actual geometry?]
   (or (= expected actual)
       (cond
         (and geometry? (number? expected) (number? actual))
         (mth/close? expected actual 1e-10)

         (and geometry? (gmt/matrix? expected) (gmt/matrix? actual))
         (every? #(same-state? (get expected %) (get actual %) true) [:a :b :c :d :e :f])

         (and geometry? (gpt/point? expected) (gpt/point? actual))
         (every? #(same-state? (get expected %) (get actual %) true) [:x :y])

         (and (map? expected) (map? actual) (= (set (keys expected)) (set (keys actual))))
         (every? (fn [key]
                   (same-state? (get expected key) (get actual key)
                                (or geometry?
                                    (and (contains? expected :parent-id)
                                         (contains? #{:x :y :width :height :rotation :points
                                                      :selrect :transform :transform-inverse} key)))))
                 (keys expected))

         (and (sequential? expected) (sequential? actual) (= (count expected) (count actual)))
         (every? true? (map #(same-state? %1 %2 geometry?) expected actual))

         :else false))))

(defn compare-states
  "Record a failure of `phase` at `step` unless the normalized files
   `expected` and `actual` are equal. Phases: `:undo`, `:redo`,
   `:no-undo-entry` (a step that added no entry changed the file),
   `:variant-undo` and `:variant-redo`."
  [situation step phase expected actual]
  (if (same-state? expected actual)
    situation
    (add-failure situation {:phase     phase
                            :step      step
                            :signature (or (not-empty (signature expected actual))
                                           #{[:unexplained]})
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

(defn with-failures-of
  "`situation` with the failures recorded in `other`, a later situation of
   the same variant."
  [situation other]
  (assoc situation ::failures (failures other)))

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

(defn- for-runner?
  [{:keys [runners]} runner]
  (or (nil? runners) (contains? runners runner)))

(defn applying-marks
  "The known failures of `case-map` that apply to `situation`, one of its
   variants: marks of its runner whose `:op` ran and whose `:when` holds."
  [situation case-map]
  (let [marks (get-in case-map [:undo-check :known-failures])]
    (run! check-mark! marks)
    (filterv (fn [{:keys [op] pred :when :as mark}]
               (and (for-runner? mark (::runner situation))
                    (or (nil? op) (tm/applied? situation op))
                    (or (nil? pred) (pred situation))))
             marks)))

(defn check-marks-applied!
  "Assert that every known failure of `case-map` for `runner` applied to at
   least one variant; `applied` holds the marks that did (see
   `applying-marks`). A mark that applies nowhere pins nothing."
  [case-map runner applied]
  (when (enabled? case-map)
    (doseq [{:keys [bug] :as mark} (get-in case-map [:undo-check :known-failures])
            :when (and (for-runner? mark runner)
                       (not (contains? (set applied) mark)))]
      (t/is false (str "Known undo failure " bug " applies to no variant of the "
                       (name runner) " runner")))))

(defn verdict
  "The failures of `situation`, a variant of `case-map`, that no known failure
   of the case pins (`:unexpected`), and the pinned phases of the known
   failures that apply to the variant but did not fail as pinned
   (`:resolved`, each the mark with its `:phase`)."
  [situation case-map]
  (let [known (applying-marks situation case-map)
        found (failures situation)]
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
