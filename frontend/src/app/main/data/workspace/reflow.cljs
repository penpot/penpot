;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.reflow
  "Tracks the ids that have layout/reflow work in flight, broken down by the
  kind of work so we can tell which type of reflow is blocking each id.

  Pending work is stored as `{id -> {kind -> #{task-id}}}`, where ids are page
  object ids plus, for `:sync-file`, the id of the file being synced. Every
  producer opens an exact task with `start!` and closes that same task with
  `finish!`.
  Tasks belong to a workspace generation, so a delayed completion from a
  finalized workspace cannot drain work opened after the workspace reloads.

  Observable producers should use `with-pending`; imperative renderer work
  should use `run-pending!`. Direct task lifecycle calls are reserved for Potok
  pipelines whose operation has to cross event or batching boundaries.

  Kinds correspond to the pipelines that schedule the work:
    :layout        flex/grid layout reflow      (shape-layout)
    :text-resize   text geometry resize         (wasm-text, texts)
    :text-measure  DOM text measurement         (texts)
    :text-position DOM text fragment geometry   (texts)
    :text-bridge   change awaiting its pipeline (texts)
    :font          font change measurement      (texts)
    :sync-file     component/library propagation (libraries)"
  (:require
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

;; Feeder subject receiving task lifecycle messages, scanned into the
;; `pending-shapes` map.
(defonce ^:private reflow-input (rx/subject))

(defonce ^:private workspace-generation (atom 0))
(defonce ^:private next-task-id (atom 0))

(defn- add-task
  [acc {:keys [id kind ids]}]
  (reduce (fn [m shape-id]
            (update-in m [shape-id kind] (fnil conj #{}) id))
          acc
          ids))

(defn- remove-task
  [acc {:keys [id kind ids]}]
  (let [task-id id]
    (reduce (fn [m shape-id]
              (let [tasks (disj (get-in m [shape-id kind] #{}) task-id)
                    kinds (if (seq tasks)
                            (assoc (get m shape-id) kind tasks)
                            (dissoc (get m shape-id) kind))]
                (if (seq kinds)
                  (assoc m shape-id kinds)
                  (dissoc m shape-id))))
            acc
            ids)))

;; Single-task operations are wrapped as batches before reaching the reducer.
(defn- reducer
  [acc {:keys [op tasks ids]}]
  (case op
    :add    (reduce add-task acc tasks)
    :remove (reduce remove-task acc tasks)
    :cancel (apply dissoc acc ids)
    :reset  {}
    acc))

;; Holds pending tasks and replays them to new waiters.
;; Reloads rebuild the scan with the latest reducer.
(def ^:private pending-shapes (rx/behavior-subject {}))

(defonce ^:private pending-subscription (atom nil))

(defn- install-pending-subscription!
  []
  ;; Settle the old scan before installing the new one.
  (swap! workspace-generation inc)
  (rx/push! reflow-input {:op :reset})
  (when-let [subscription @pending-subscription]
    (rx/dispose! subscription))
  (reset! pending-subscription
          (rx/sub! (->> reflow-input (rx/scan reducer {}))
                   pending-shapes))
  (rx/push! reflow-input {:op :reset}))

(install-pending-subscription!)

(defn task
  "Creates an opaque task token without opening it."
  [kind ids]
  {:id (swap! next-task-id inc)
   :generation @workspace-generation
   :kind kind
   :ids (into #{} ids)})

(defn- push-tasks!
  [op tasks]
  ;; Empty and stale tasks must not affect the active workspace.
  (let [generation @workspace-generation
        tasks      (into [] (filter #(and (seq (:ids %))
                                          (= (:generation %) generation)))
                         tasks)]
    (when (seq tasks)
      (rx/push! reflow-input {:op op :tasks tasks}))
    tasks))

(defn- start-tasks!
  "Opens task tokens in one pending-map update."
  [tasks]
  (push-tasks! :add tasks))

(defn start!
  "Opens and returns a task. The one-argument form opens a token created with
  `task`; the two-argument form creates and opens it in one step."
  ([task]
   (push-tasks! :add [task])
   task)
  ([kind ids]
   (start! (task kind ids))))

(defn finish-tasks!
  "Closes task tokens from the active workspace generation in one update."
  [tasks]
  (push-tasks! :remove tasks)
  nil)

(defn finish!
  "Closes `task` if it belongs to the active workspace generation. Repeated or
  stale completion is a no-op."
  [task]
  (finish-tasks! [task]))

(defn reset-pending!
  "Starts a new workspace generation and forgets every task from the old one."
  []
  (swap! workspace-generation inc)
  (rx/push! reflow-input {:op :reset}))

(defn cancel-shapes!
  "Forgets pending work attached to shapes that no longer exist."
  [ids]
  (when (seq ids)
    (rx/push! reflow-input {:op :cancel :ids ids})))

(defn with-pending
  "Runs observable `ob` as tracked layout work. Prefer this entry point for
  observable producers: it owns task activation and finalization, including
  errors and unsubscription. An `ob` that is built but never subscribed marks
  nothing."
  [kind ids ob]
  (->> (rx/of ::subscribe)
       (rx/mapcat (fn [_]
                    (let [task (start! kind ids)]
                      (rx/finalize #(finish! task) ob))))))

(defn run-pending!
  "Runs zero-argument promise operation `f` as tracked layout work. The task is
  opened before `f` runs and is finalized when its returned promise settles.
  Synchronous failures also finalize the task before being rethrown.

  Prefer this entry point for imperative or renderer-driven producers."
  [kind ids f]
  (let [task (start! kind ids)]
    (try
      (p/finally (f) #(finish! task))
      (catch :default cause
        (finish! task)
        (throw cause)))))

(defn bridge-pending
  "Keeps each id pending until matching work starts."
  [ids target-kinds bridge-kind]
  (let [ids (into #{} ids)]
    (if (empty? ids)
      (rx/empty)
      (rx/create
       (fn [subs]
         ;; Separate tasks let renderer work release each shape independently.
         (let [tasks-by-id
               (into {} (map (fn [id] [id (task bridge-kind [id])])) ids)

               remaining
               (atom ids)

               release!
               (fn [released]
                 (let [released (into #{} (filter @remaining) released)]
                   (when (seq released)
                     (finish-tasks! (map tasks-by-id released))
                     (swap! remaining #(apply disj % released))
                     (when (empty? @remaining)
                       (rx/end! subs)))))

               matching-task-ids
               (fn [tasks]
                 (into #{}
                       (comp
                        (filter #(contains? target-kinds (:kind %)))
                        (mapcat :ids)
                        (filter ids))
                       tasks))

               ;; Listen before opening bridges so synchronous work is not missed.
               lifecycle-sub
               (rx/sub!
                reflow-input
                (fn [{:keys [op tasks ids]}]
                  (case op
                    :add
                    (release! (matching-task-ids tasks))

                    :cancel
                    (release! ids)

                    :reset
                    (release! @remaining)

                    nil)))

               _
               (start-tasks! (vals tasks-by-id))]
           (fn []
             (rx/dispose! lifecycle-sub)
             (when (seq @remaining)
               (finish-tasks! (map tasks-by-id @remaining))
               (reset! remaining #{})))))))))

(defn settled
  "Observable that emits once every id in `ids` has drained from the pending
  map, then completes. A nil `ids` waits for every pending id; an empty one has
  nothing to wait for. Replays on subscribe, so an already drained map emits
  immediately.

  Callers waiting on one shape pass its whole subtree: reflow work lands either
  on the shape (a board laying out its children) or on its descendants (a group
  whose texts are re-measured)."
  [ids]
  (let [done? (if (some? ids)
                (fn [pending] (not-any? #(contains? pending %) ids))
                empty?)]
    (->> pending-shapes
         (rx/filter done?)
         (rx/take 1))))
