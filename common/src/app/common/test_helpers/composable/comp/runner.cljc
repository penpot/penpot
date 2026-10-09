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
       `ch/components-changed` reports, then the components each sync changed,
       until none is left. The frontend component watcher does the same, one
       pass per sync commit.
     - Undo and redo. A user operation and the syncs it caused form one undo
       group. `n/undo` reverts the latest group and `n/redo` applies again the
       latest reverted one; neither syncs, as in the frontend, where an undo
       or redo commit only bumps the component's `:modified-at`. A user
       operation that pushes a group empties the redo stack.

   Assembly operations (create, instantiate, nest) change the file with no sync
   and no undo group, like the frontend interpreter's file installs.

   Operations whose result needs frontend code (kind `:frontend-only`) are
   rejected. Not modelled: the `:modified-at` bump of a changed component,
   and the frontend follow-ups of a swap or switch (token propagation, WASM
   text resize, layout update)."
  (:require
   [app.common.files.changes :as ch]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]))

(def ^:private default-opts
  {:sync? true})

;; A guard against a sync cascade that never settles.
(def ^:private max-sync-passes 100)

(defn- changed-components
  "Ids of the components whose main `changes` modified, judged against
   `old-data`, the file data before `changes` applied."
  [old-data changes]
  (into #{}
        (mapcat (partial ch/components-changed old-data))
        (:redo-changes changes)))

(defn- libraries
  "The situation's files by id, with `file` as its current file."
  [situation file]
  (assoc (tm/aux-files situation) (:id file) file))

(defn- validate-settled!
  "Validate the situation's file, resolving components against its other
   files. Only a settled file is valid: between two passes of a sync cascade,
   an outer copy can still point at the component its main no longer uses.
   Throws when the file is invalid."
  [situation]
  (let [file (tm/file situation)]
    (thf/validate-file! file (libraries situation file))
    situation))

(defn- component-sync-changes
  "The changes that sync the copies of `component-id` in `file`, as the
   frontend `sync-file` event builds them for a local component."
  [situation file component-id]
  (let [file-id (:id file)]
    (cll/generate-sync-file-changes (pcb/empty-changes)
                                    nil
                                    :components
                                    file-id
                                    component-id
                                    file-id
                                    (libraries situation file)
                                    file-id)))

(defn- sync-file
  "Sync `situation`'s file, starting from the components that `changes`
   modified (judged against `old-data`), until no component is left to sync,
   and validate the result. Returns `[situation syncs]`, with `syncs` the
   applied sync changes in order."
  [situation old-data changes]
  (loop [file    (tm/file situation)
         pending (vec (changed-components old-data changes))
         syncs   []
         passes  0]
    (cond
      (empty? pending)
      [(validate-settled! (tm/with-file situation file)) syncs]

      (>= passes max-sync-passes)
      (throw (ex-info "Component sync did not settle"
                      {:type ::sync-not-settled
                       :pending pending
                       :passes passes}))

      :else
      (let [component-id (first pending)
            sync         (component-sync-changes situation file component-id)
            file'        (thf/apply-changes file sync :validate? false)
            more         (->> (changed-components (:data file) sync)
                              (remove (set pending)))]
        (recur file'
               (into (subvec pending 1) more)
               (cond-> syncs (seq (:redo-changes sync)) (conj sync))
               (inc passes))))))

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
  "Apply to the situation's file the `side` changes (`:undo-changes` or
   `:redo-changes`) of each changes in `group`, in order, and validate the
   result."
  [situation side group]
  (let [file (reduce (fn [file changes]
                       (thf/apply-changes file
                                          {:redo-changes (get changes side)}
                                          :validate? false))
                     (tm/file situation)
                     group)]
    (-> situation
        (tm/with-file file)
        (validate-settled!))))

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
  [situation op]
  (let [[situation group] (undo-group situation)]
    (tm/record-application situation op {:undone (count group)})))

(defn- run-redo
  [situation op]
  (let [[situation group] (redo-group situation)]
    (tm/record-application situation op {:redone (count group)})))

(defn- op-kind
  "The kind of a leaf operation (see `n/IComponentOperation`), or nil for an
   engine operation such as `Test` or `Skip`."
  [op]
  (when (satisfies? n/IComponentOperation op)
    (n/op-kind op)))

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

(defn- run-node
  "Apply one leaf operation of `kind`. A user operation is followed by its sync
   (when `:sync?`) and pushes its undo group."
  [situation op kind {:keys [sync?]}]
  (let [old-data                      (:data (tm/file situation))
        [recorded? changes situation] (n/take-changes (tm/apply-to op situation))]
    (check-recorded-changes! op kind recorded?)
    (if (nil? changes)
      situation
      (let [[situation syncs] (if sync?
                                (sync-file situation old-data changes)
                                [situation []])]
        (push-undo-group situation (into [changes] syncs))))))

(defn- run-op
  [situation op opts]
  (cond
    (tm/sequence? op)
    (reduce #(run-op %1 %2 opts) situation (:steps op))

    (tm/recorded-choice? op)
    (-> (tm/record-choice situation op)
        (run-op (tm/choice-of op) opts))

    :else
    (let [kind (op-kind op)]
      (case kind
        :undo
        (run-undo situation op)

        :redo
        (run-redo situation op)

        :frontend-only
        (throw (ex-info (str "The pure runner cannot run " (pr-str (type op))
                             ": its production result needs frontend code. "
                             "Run the case through the frontend interpreter.")
                        {:type ::frontend-only
                         :op op}))

        (run-node situation op kind opts)))))

(defn undo-depth
  "How many undo groups the runner holds in `situation`."
  [situation]
  (count (get situation ::undo-stack)))

(defn redo-depth
  "How many redo groups the runner holds in `situation`."
  [situation]
  (count (get situation ::redo-stack)))

(defn run-variant
  "Run one concrete (already enumerated) variant: build a fresh situation with
   `setup` and run `operation` with sync and undo, returning the resulting
   situation. Options: `:sync?` (default true) syncs after each user
   operation."
  ([case-map] (run-variant case-map {}))
  ([{:keys [setup operation]} opts]
   (run-op (setup) operation (merge default-opts opts))))

(defn run-all
  "Enumerate `operation` into its concrete variants and run each with
   `run-variant`, resetting the label map before each setup. Returns the
   resulting situations in enumeration order."
  ([case-map] (run-all case-map {}))
  ([case-map opts]
   (tm/run-all case-map #(run-variant % opts))))
