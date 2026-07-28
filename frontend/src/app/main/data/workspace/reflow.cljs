;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.reflow
  "Tracks the shape ids that have layout/reflow work in flight, broken down by
  the kind of work so we can tell which type of reflow is blocking each shape.

  Pending work is stored as `{shape-id -> {kind -> #{task-id}}}`. Every producer
  opens an exact task with `start!` and closes that same task with `finish!`.
  Tasks belong to a workspace generation, so a delayed completion from a
  finalized workspace cannot drain work opened after the workspace reloads.

  Observable producers should use `with-pending`; imperative renderer work
  should use `run-pending!`. Direct task lifecycle calls are reserved for Potok
  pipelines whose operation has to cross event or batching boundaries.

  Kinds correspond to the pipelines that schedule the work:
    :layout        flex/grid layout reflow      (shape-layout)
    :text-resize   text geometry resize         (wasm-text, texts)
    :text-measure  DOM text measurement         (texts)
    :text-bridge   change awaiting its pipeline (texts)
    :font          font change measurement      (texts)"
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

(defn- reducer
  [acc {:keys [op task ids]}]
  (case op
    :add    (add-task acc task)
    :remove (remove-task acc task)
    :cancel (apply dissoc acc ids)
    :reset  {}
    acc))

;; Behaviour subject holding `{shape-id -> {kind -> #{task-id}}}`.
;; It replays its current value synchronously to new subscribers, which gives
;; `wait-for-layout-update` a free fast-path when there is nothing pending.
(defonce ^:private pending-shapes
  (let [sub (rx/behavior-subject {})]
    (rx/sub! (->> reflow-input (rx/scan reducer {})) sub)
    sub))

(defn task
  "Creates an opaque task token without opening it."
  [kind ids]
  {:id (swap! next-task-id inc)
   :generation @workspace-generation
   :kind kind
   :ids (into #{} ids)})

(defn start!
  "Opens and returns a task. The one-argument form opens a token created with
  `task`; the two-argument form creates and opens it in one step."
  ([task]
   (when (and (seq (:ids task))
              (= (:generation task) @workspace-generation))
     (rx/push! reflow-input {:op :add :task task}))
   task)
  ([kind ids]
   (start! (task kind ids))))

(defn finish!
  "Closes `task` if it belongs to the active workspace generation. Repeated or
  stale completion is a no-op."
  [{:keys [generation ids] :as task}]
  (when (and (seq ids)
             (= generation @workspace-generation))
    (rx/push! reflow-input {:op :remove :task task})))

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

(defn pending-signal
  "Emits once any of `kinds` is pending for any of `ids`, then completes.
  Emits right away when that work is already in flight."
  [ids kinds]
  (letfn [(id-pending? [pending id]
            (some (partial contains? (get pending id)) kinds))

          (any-pending? [pending]
            (some (partial id-pending? pending) ids))]
    (->> pending-shapes
         (rx/filter any-pending?)
         (rx/take 1))))

;; Ceiling for callers that pass no timeout, so a pipeline that never drains
;; its marks rejects the promise rather than leaving it unsettled.
(def ^:private default-timeout 30000)

(defn wait-for-layout-update
  "Returns a JS Promise that resolves when every id in `shape-ids` has drained
  from the pending map. A nil `shape-ids` waits for every pending shape; an
  empty one has nothing to wait for and resolves right away. The promise is
  rejected when `timeout` (ms) elapses first; a nil `timeout` uses
  `default-timeout`.

  Callers waiting on one shape pass its whole subtree: reflow work lands either
  on the shape (a board laying out its children) or on its descendants (a group
  whose texts are re-measured)."
  ([timeout]
   (wait-for-layout-update nil timeout))
  ([shape-ids timeout]
   (js/Promise.
    (fn [resolve reject]
      (let [timeout (or timeout default-timeout)

            done?   (if (some? shape-ids)
                      (fn [pending] (not-any? #(contains? pending %) shape-ids))
                      empty?)

            settled (->> pending-shapes
                         (rx/filter done?)
                         (rx/map (constantly :ok)))

            ;; Race the settle signal against the deadline; the loser is
            ;; unsubscribed. `settled` replays on subscribe, so an already
            ;; drained map wins even against a 1ms deadline.
            source  (rx/race (->> (rx/of :timeout)
                                  (rx/delay timeout))
                             settled)]
        (->> source
             (rx/take 1)
             (rx/subs!
              (fn [value]
                (if (= value :timeout)
                  (reject (js/Error. "waitForLayoutUpdate timeout"))
                  (resolve)))
              reject)))))))
