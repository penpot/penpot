;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.reflow
  "Tracks the shape ids that have layout/reflow work in flight, broken down by
  the kind of work so we can tell which type of reflow is blocking each shape.

  Pending work is stored as a nested refcount map `{shape-id -> {kind -> count}}`:
  a shape is pending while it has at least one kind with a count greater than
  zero. The count (instead of a plain set) is required because several reflow
  operations of the same kind can target the same shape while an earlier one is
  still in flight; each `mark-pending!` must be balanced by exactly one
  `mark-done!` of the same kind and ids so an operation finishing doesn't resolve
  waiters that are still blocked on another operation for the same shape.

  Kinds correspond to the pipelines that schedule the work:
    :layout       flex/grid layout reflow      (shape-layout)
    :text-resize  wasm text geometry resize    (wasm-text)
    :font         font change measurement      (texts)"
  (:require
   [beicon.v2.core :as rx]))

;; Feeder subject receiving `{:op .. :kind .. :ids ..}` messages from
;; mark-pending! / mark-done! / reset-pending!; scanned into the
;; `pending-shapes` refcount map.
(defonce ^:private reflow-input (rx/subject))

;; Increments the `kind` refcount of each id (adding the shape/kind starting at 1).
(defn- inc-ids
  [acc kind ids]
  (reduce (fn [m id] (update-in m [id kind] (fnil inc 0))) acc ids))

;; Decrements the `kind` refcount of each id, dropping the kind once it reaches
;; zero and the shape once it has no pending kinds left (decrementing an absent
;; id/kind is a no-op).
(defn- dec-ids
  [acc kind ids]
  (reduce (fn [m id]
            (let [n     (dec (get-in m [id kind] 0))
                  kinds (if (pos? n)
                          (assoc (get m id) kind n)
                          (dissoc (get m id) kind))]
              (if (seq kinds)
                (assoc m id kinds)
                (dissoc m id))))
          acc ids))

;; Applies one `{:op .. :kind .. :ids ..}` message to the pending map: :add
;; increments, :remove decrements, :reset clears everything.
(defn- reducer
  [acc {:keys [op kind ids]}]
  (case op
    :add    (inc-ids acc kind ids)
    :remove (dec-ids acc kind ids)
    :reset  {}
    acc))

;; Behaviour subject holding the current pending map `{shape-id -> {kind -> count}}`.
;; It replays its current value synchronously to new subscribers, which gives
;; `wait-for-layout-update` a free fast-path when there is nothing pending.
(defonce ^:private pending-shapes
  (let [sub (rx/behavior-subject {})]
    (rx/sub! (->> reflow-input (rx/scan reducer {})) sub)
    sub))

;; NOTE: do not dedupe `ids` — multiplicity must be preserved so each
;; `mark-pending!` is balanced by exactly one `mark-done!` of the same kind and ids.
(defn mark-pending!
  [kind ids]
  (rx/push! reflow-input {:op :add :kind kind :ids ids}))

(defn mark-done!
  [kind ids]
  (rx/push! reflow-input {:op :remove :kind kind :ids ids}))

(defn reset-pending!
  []
  (rx/push! reflow-input {:op :reset}))

(defn wait-for-layout-update
  "Returns a JS Promise that resolves when `shape-id` (or, when nil, every
  pending shape) has drained from the pending map. When `timeout` (ms) is
  provided and elapses first, the promise is rejected. The single-arity form
  waits for every pending shape (shape-id nil)."
  ([timeout]
   (wait-for-layout-update nil timeout))
  ([shape-id timeout]
   (js/Promise.
    (fn [resolve reject]
      (let [done? (fn [pending]
                    (if shape-id
                      (not (contains? pending shape-id))
                      (empty? pending)))

            settled (->> pending-shapes
                         (rx/filter done?)
                         (rx/map (constantly :ok)))

            ;; Race the settle signal against the optional deadline; whichever
            ;; reacts first wins (and the loser is unsubscribed). Without a
            ;; timeout we just wait on the settle signal.
            source (if timeout
                     (rx/race (->> (rx/of :timeout)
                                   (rx/delay timeout))
                              settled)
                     settled)]
        (->> source
             (rx/take 1)
             (rx/subs!
              (fn [value]
                (if (= value :timeout)
                  (reject (js/Error. "waitForLayoutUpdate timeout"))
                  (resolve)))
              reject)))))))
