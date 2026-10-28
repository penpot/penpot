;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.runner
  "Pure runner for composable component cases. It runs a case on the in-memory
   file, without the frontend store, on the JVM and in CLJS, and adds what the
   frontend workspace does around each operation:

     - Sync. After a user operation (kind `:user`, see
       `n/IComponentOperation`), it syncs each component that
       `cll/changed-components` reports, then the components each sync changed,
       until none is left. The frontend component watcher does the same, one
       pass per sync commit.
     - Undo and redo. A user operation and the syncs it caused form one undo
       group. `n/undo` reverts the latest groups and `n/redo` applies again the
       latest reverted one; neither syncs, as in the frontend, where an undo
       or redo commit only bumps the component's `:modified-at`. A user
       operation that pushes a group empties the redo stack.

   Assembly operations (create, instantiate, nest) change the file with no sync
   and no undo group, like the frontend interpreter's file installs.

   Every variant also runs the undo/redo round trip of
   `app.common.test-helpers.composable.comp.undo-check`.

   Operations whose result needs frontend code (kind `:frontend-only`) are
   rejected. Not modelled: the `:modified-at` bump of a changed component,
   and the frontend follow-ups of a swap or switch (token propagation, WASM
   text resize, layout update)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.composable.comp.files :as cf]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]))

(def ^:private default-opts
  {:sync? true})

(def changed-components
  "Ids of the components whose main `changes` modified, judged against
   `old-data`, the file data before `changes` applied."
  cll/changed-components)

(defn- validate-settled!
  "Validate the situation's file, resolving components against its other
   files. Only a settled file is valid: between two passes of a sync cascade,
   an outer copy can still point at the component its main no longer uses.
   Throws when the file is invalid."
  [situation]
  (let [files (cf/all-files situation)]
    (doseq [[id file] files]
      (thf/validate-file! file (cf/libraries (-> situation
                                                 (tm/with-file file)
                                                 (tm/with-aux-files (dissoc files id))))))
    situation))

(defn sync-cascade
  "Sync the copies of `component-ids` in `file`, then those of the
   components each sync changed, until none is left, as the frontend
   `sync-file` event does (`cll/generate-sync-components-cascade`).
   `libraries` are the other files by id. Returns `{:file :syncs
   :passes}`, with `syncs` the sync changes, if they changed something."
  [file libraries component-ids]
  (let [file-id (:id file)
        {:keys [changes data passes]}
        (cll/generate-sync-components-cascade (pcb/empty-changes)
                                              file-id
                                              component-ids
                                              (assoc libraries file-id file)
                                              file-id)]
    {:file   (assoc file :data data)
     :syncs  (if (seq (:redo-changes changes)) [changes] [])
     :passes passes}))

(defn- sync-with-layout
  "Layout property edits also reflow their copies after sync. Keep each
   derived pass so the primary action owns its full inverse."
  [situation component-ids reflow?]
  (loop [situation situation component-ids component-ids syncs [] passes 0]
    (let [synced  (cf/sync-world situation component-ids)
          before  (:situation synced)
          syncs   (into syncs (:syncs synced))
          changes (when reflow? (n/get-layout-reflow-changes before))]
      (if (empty? (:redo-changes changes))
        {:situation before :syncs syncs}
        (let [after (cf/apply-changes before changes)]
          ;; Grid order writers may record an already-correct order.
          (if (= (:data (tm/file before)) (:data (tm/file after)))
            {:situation before :syncs syncs}
            (do
              (when (>= passes 20)
                (throw (ex-info "Layout did not settle after component sync"
                                {:type ::layout-not-settled})))
              (recur after (cf/changed-components before after changes)
                     (conj syncs changes) (inc passes)))))))))

(defn- sync-file
  [situation before changes]
  (let [{:keys [situation syncs]}
        (sync-with-layout situation (cf/changed-components before situation changes)
                          (n/layout-reflow? changes))]
    [(validate-settled! situation) syncs]))

(defn- push-undo-group
  "Add `group`, the changes of one user operation and its syncs in order, to
   the undo stack and empty the redo stack. Changes with nothing to undo are
   left out, as the frontend undo stack leaves out commits with no undo
   changes; a group left empty changes neither stack."
  [situation group]
  (let [group (filterv (comp seq :undo-changes) group)]
    (cond-> situation
      (seq group) (-> (update ::undo-stack (fnil conj []) group)
                      (assoc ::redo-stack [])))))

(defn- apply-group
  [situation side group]
  (validate-settled!
   (reduce #(cf/apply-changes %1 %2 side) situation group)))

(defn- move-group
  "Move the latest group of the `from` stack to the `to` stack, applying its
   `side` changes, in `order` (`seq` or `rseq`). Returns `[situation group]`;
   with an empty `from` stack it changes nothing, like the frontend undo and
   redo."
  [situation from to side order]
  (let [stack (get situation from [])]
    (if (empty? stack)
      [situation nil]
      (let [group (peek stack)]
        [(-> situation
             (apply-group side (order group))
             (assoc from (pop stack))
             (update to (fnil conj []) group))
         group]))))

(defn- undo-group
  "Revert the latest undo group, newest changes first, and move it to the
   redo stack."
  [situation]
  (move-group situation ::undo-stack ::redo-stack :undo-changes rseq))

(defn- redo-group
  "Apply again the latest redo group, oldest changes first, and move it back
   to the undo stack."
  [situation]
  (move-group situation ::redo-stack ::undo-stack :redo-changes seq))

(defn- run-undo
  "Undo the latest `(:times op)` groups, one at a time."
  [situation op]
  (let [[situation undone]
        (reduce (fn [[situation undone] _]
                  (let [[situation group] (undo-group situation)]
                    [situation (+ undone (count group))]))
                [situation 0]
                (range (:times op)))]
    (tm/record-application situation op {:undone undone})))

(defn- run-redo
  [situation op]
  (let [[situation group] (redo-group situation)]
    (tm/record-application situation op {:redone (count group)})))

(def ^:private op-kinds
  #{:assembly :user :undo :redo :frontend-only})

(defn- op-kind
  "The kind of a leaf operation (see `n/IComponentOperation`), or nil for an
   engine operation (`Test`, `Skip`). Throws for any other operation or kind,
   which the runner would neither sync nor check."
  [op]
  (cond
    (satisfies? n/IComponentOperation op)
    (let [kind (n/op-kind op)]
      (when-not (contains? op-kinds kind)
        (throw (ex-info (str "Unknown operation kind " (pr-str kind))
                        {:type ::unknown-op-kind :op op :kind kind})))
      kind)

    (tm/engine-op? op)
    nil

    :else
    (throw (ex-info (str (pr-str (type op)) " does not implement "
                         "`n/IComponentOperation`")
                    {:type ::unknown-op :op op}))))

(defn- check-recorded-changes!
  "Throw unless `op` recorded its changes exactly when it is a user operation."
  [op kind recorded?]
  (cond
    (and (= kind :user) (not recorded?))
    (throw (ex-info (str "User operation " (pr-str (type op)) " recorded no "
                         "changes. Call `n/record-changes`, with nil when it "
                         "changed nothing.")
                    {:type ::changes-not-recorded
                     :op op}))

    (and (not= kind :user) recorded?)
    (throw (ex-info (str (pr-str (type op)) " recorded changes but its kind is "
                         (pr-str kind) ". Only user operations record them.")
                    {:type ::unexpected-changes
                     :op op
                     :kind kind}))))

(defn undo-depth
  "How many undo groups the runner holds in `situation`."
  [situation]
  (count (get situation ::undo-stack)))

(defn redo-depth
  "How many redo groups the runner holds in `situation`."
  [situation]
  (count (get situation ::redo-stack)))

(defn history
  "The undo/redo state of one editor, without its loaded file snapshots."
  [situation]
  (select-keys situation [::undo-stack ::redo-stack]))

(defn with-history
  [situation history]
  (merge (dissoc situation ::undo-stack ::redo-stack) history))

(defn- snapshot
  [situation]
  (let [file (tm/file situation)]
    (uc/snapshot-files (assoc (tm/aux-files situation) (:id file) file))))

(defn- attempt
  "`[(f) nil]`, or `[nil error]` when `f` throws."
  [f]
  (try
    [(f) nil]
    (catch #?(:clj Throwable :cljs :default) e
      [nil e])))

(defn- round-trip-phase
  "Run `f` (situation -> situation) on `from` as round-trip `phase` at `step`.
   Returns `[situation nil]`, or, when `f` throws, `[nil failed]`, with
   `failed` the situation the variant goes on from (`back-to`) holding the
   failures of `from` plus the error."
  [from back-to step phase f]
  (let [[result e] (attempt #(f from))]
    (if e
      [nil (uc/with-failures-of
             back-to
             (uc/add-error from step phase (str (ex-message e) " " (pr-str (ex-data e)))
                           (or (:type (ex-data e)) :exception)))]
      [result nil])))

(defn- check-step
  "The round trip of user step `op` (see `uc`): `before` is the normalized
   file before it and `depth` the undo depth before it. The variant goes on
   from the redone situation, or from the one after the step when undo or
   redo throws."
  [situation op before depth]
  (let [step  (uc/step situation op)
        after (snapshot situation)]
    (if (= depth (undo-depth situation))
      (uc/compare-states situation step :no-undo-entry before after)
      (let [[undone failed] (round-trip-phase situation situation step :undo
                                              (comp first undo-group))]
        (or failed
            (let [undone          (uc/compare-states undone step :undo before (snapshot undone))
                  [redone failed] (round-trip-phase undone situation step :redo
                                                    (comp first redo-group))]
              (or failed
                  (uc/compare-states redone step :redo after (snapshot redone)))))))))

(defn- check-variant
  "The end-of-variant round trip (see `uc`): undo back to the baseline, then
   redo all. Returns `situation` with the failures the round trip found, so
   the asserter judges the state the operations produced."
  [situation]
  (let [{:keys [depth] :as baseline} (uc/baseline situation)
        times (- (undo-depth situation) depth)
        final (snapshot situation)
        times-of (fn [f] #(nth (iterate (comp first f) %) times))]
    (assert (not (neg? times)) "the undo stack is below the round trip's baseline")
    (if (pos? times)
      (let [[undone failed] (round-trip-phase situation situation nil :variant-undo
                                              (times-of undo-group))]
        (or failed
            (let [undone          (uc/compare-states undone nil :variant-undo
                                                     (:snapshot baseline) (snapshot undone))
                  [redone failed] (round-trip-phase undone situation nil :variant-redo
                                                    (times-of redo-group))]
              (uc/with-failures-of
                situation
                (or failed
                    (uc/compare-states redone nil :variant-redo final (snapshot redone)))))))
      situation)))

(defn- mark-baseline
  [situation]
  (uc/mark-baseline situation (undo-depth situation) (snapshot situation)))

(defn- run-node
  "Apply one leaf operation of `kind`. A user operation is followed by its sync
   (when `:sync?`), pushes its undo group, and gets its round trip (when
   `:undo-check?`). An assembly operation moves the round trip's baseline."
  [situation op kind {:keys [sync? undo-check?]}]
  (let [before-situation              situation
        before                        (when undo-check? (snapshot situation))
        depth                         (undo-depth situation)
        [recorded? changes situation] (n/take-changes (tm/apply-to op situation))]
    (check-recorded-changes! op kind recorded?)
    (cond-> (if (nil? changes)
              situation
              (let [[situation syncs] (if sync?
                                        (sync-file situation before-situation changes)
                                        [situation []])]
                (cond-> (push-undo-group situation (into [changes] syncs))
                  (and (not sync?) (n/layout-reflow? changes))
                  (assoc ::pending-layout? true)

                  (not sync?)
                  (update ::pending-components (fnil into #{})
                          (cf/changed-components before-situation situation changes)))))
      (and undo-check? (= kind :user))
      (check-step op before depth)

      (and undo-check? (= kind :assembly))
      (mark-baseline))))

(defn- run-op
  [situation op opts]
  (cond
    (tm/sequence? op)
    (reduce #(run-op %1 %2 opts) situation (:steps op))

    (tm/recorded-choice? op)
    (-> (tm/record-choice situation op)
        (run-op (tm/choice-of op) opts))

    :else
    (let [kind      (op-kind op)
          situation (case kind
                      :undo
                      (let [situation (run-undo situation op)]
                        ;; never undo past the state a case's own undo went back to
                        (cond-> situation
                          (and (:undo-check? opts)
                               (< (undo-depth situation) (:depth (uc/baseline situation))))
                          (mark-baseline)))

                      :redo
                      (run-redo situation op)

                      :frontend-only
                      (throw (ex-info (str "The pure runner cannot run " (pr-str (type op))
                                           ": its production result needs frontend code. "
                                           "Run the case through the frontend interpreter.")
                                      {:type ::frontend-only
                                       :op op}))

                      (run-node situation op kind opts))]
      (when-let [observe (:observe opts)]
        (when (contains? #{:user :undo :redo} kind)
          (observe situation)))
      situation)))

(defn run-variant
  "Run one concrete (already enumerated) variant: build a fresh situation with
   `setup` and run `operation` with sync and undo, returning the resulting
   situation. Options:
     - `:sync?` (default true) syncs after each user operation;
     - `:undo-check?` (default true, false when the case map turns the check
       off) runs the undo/redo round trip of
       `app.common.test-helpers.composable.comp.undo-check`; its failures are
       recorded in the situation for `uc/check!`;
     - `:observe`, a function called with the situation after each user,
       undo and redo operation settles (the golden master records them)."
  ([case-map] (run-variant case-map {}))
  ([{:keys [setup operation] :as case-map} opts]
   (let [opts (merge default-opts
                     {:undo-check? (uc/enabled? case-map)}
                     opts)]
     (cond-> (if (:undo-check? opts)
               (-> (setup)
                   (uc/with-runner :pure)
                   (mark-baseline)
                   (run-op operation opts)
                   (check-variant))
               (run-op (setup) operation opts))
       ;; assembly steps and a user step that changed nothing are not
       ;; validated on their own
       (:sync? opts) (validate-settled!)))))

(defn step
  "Run a resolved random operation, retaining the runner's undo stacks.
   The harness checks each settled group's inverse itself."
  [situation op]
  (run-op situation op {:sync? false :undo-check? false}))

(defn pending-components
  [situation]
  (get situation ::pending-components #{}))

(defn settle
  "Sync selected pending components in the latest user's undo group."
  ([situation] (settle situation (pending-components situation)))
  ([situation component-ids]
   (let [{:keys [situation syncs]} (sync-with-layout situation component-ids (::pending-layout? situation))
         stack (get situation ::undo-stack [])]
     (cond-> (-> situation
                 (dissoc ::pending-layout?)
                 (update ::pending-components #(apply disj (or % #{}) component-ids)))
       (and (seq syncs) (seq stack))
       (assoc ::undo-stack (conj (pop stack) (into (peek stack) syncs)))))))

(defn run-all
  "Enumerate `operation` into its concrete variants and run each with
   `run-variant`, resetting the label map before each setup. Returns the
   resulting situations in enumeration order."
  ([case-map] (run-all case-map {}))
  ([case-map opts]
   (tm/run-all case-map #(run-variant (merge case-map %) opts))))

(defn run-each
  "Run every variant of `case-map` with `run-variant` and call `f` on its
   situation before the next variant runs (see `tm/run-each`)."
  ([case-map f] (run-each case-map {} f))
  ([case-map opts f]
   (tm/run-each case-map #(run-variant (merge case-map %) opts) f)))
