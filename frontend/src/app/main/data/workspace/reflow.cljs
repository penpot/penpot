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
    :layout        flex/grid layout reflow      (shape-layout)
    :text-resize   text geometry resize         (wasm-text, texts)
    :text-measure  DOM text measurement         (texts)
    :text-bridge   change awaiting its pipeline (texts)
    :font          font change measurement      (texts)"
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

(defn with-pending
  "Wraps `ob` so `ids` are marked pending for `kind` on subscription and drained
  when it terminates (complete, error or unsubscribe). An `ob` that is built but
  never subscribed marks nothing."
  [kind ids ob]
  (->> (rx/of ::subscribe)
       (rx/mapcat (fn [_]
                    (mark-pending! kind ids)
                    (rx/finalize #(mark-done! kind ids) ob)))))

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

;; How long a pipeline may hold a mark while waiting on an external signal
;; (a font download, a buffered commit) before giving up and draining. Sized for
;; the slowest of those, a font fetch.
(def ^:const stuck-timeout 10000)

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
