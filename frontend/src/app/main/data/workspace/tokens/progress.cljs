;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.progress
  "Blocks the workspace with a dialog while the shapes are set to the
  values of their tokens, when it takes long: the values, and then the
  texts and layouts they change, until the user can go on working.

  The work reports what it adds (`add-work`) and what it has done
  (`work-done`) in units of its own choosing. The dialog shows when the
  work has run for `delay-ms`, or at once (`show`) when the work knows it
  will be long (`long-work?`)."
  (:require
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.time :as ct]
   [app.main.data.workspace.reflow :as wrf]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def delay-ms
  "Time the work runs before the dialog shows."
  400)

(def paint-delay-ms
  "Pause after showing the dialog at once, so it is painted before the work
  blocks the browser."
  50)

(def long-weight
  "Weight (`cltp/target-weight`) of the token values from which their work
  shows the dialog at once."
  1000)

(def settle-timeout-ms
  "Longest wait for the texts and layouts after the values."
  30000)

(def ^:private tick-ms
  "Interval of the checks of the work."
  200)

(def ^:private quiet-ticks
  "Ticks without texts or layouts in flight before the dialog hides."
  2)

(defn long-work?
  "True when setting the token attributes `targets` of `objects` is long
  enough to show the dialog at once."
  [objects targets]
  (>= (reduce-kv (fn [weight id attrs]
                   (+ weight (cltp/target-weight (get objects id) attrs)))
                 0
                 targets)
      long-weight))

(defn add-work
  "Event that adds `n` units to the work in progress."
  [n]
  (ptk/data-event ::add-work n))

(defn work-done
  "Event that marks `n` units of the work as done."
  [n]
  (ptk/data-event ::work-done n))

(defn show
  "Event that shows the dialog."
  []
  (ptk/reify ::show
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::progress true))))

(defn- hide
  []
  (ptk/reify ::hide
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state ::progress))))

(defn- new-work
  []
  {:total 0 :done 0 :quiet 0 :started (ct/now)})

(defn- tick
  "Events that show or hide the dialog for `work*`."
  [work*]
  (when-let [work @work*]
    (let [values? (>= (:done work) (:total work))
          work    (cond-> work
                    values? (update :values-at #(or % (ct/now)))
                    :always (assoc :quiet (if (and values? (zero? (wrf/pending-count)))
                                            (inc (:quiet work))
                                            0)))]
      (cond
        (and values?
             (or (>= (:quiet work) quiet-ticks)
                 (>= (ct/diff-ms (:values-at work) (ct/now)) settle-timeout-ms)))
        (do (vreset! work* nil)
            (when (:shown? work)
              (rx/of (hide))))

        (:shown? work)
        (do (vreset! work* work)
            nil)

        (>= (ct/diff-ms (:started work) (ct/now)) delay-ms)
        (do (vreset! work* (assoc work :shown? true))
            (rx/of (show)))

        :else
        (do (vreset! work* work)
            nil)))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      ;; A dialog left by a workspace reload has no work to hide it
      (dissoc state ::progress))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [;; A workspace reload starts a new one, which replaces this one
            stopper (rx/filter #(or (ptk/type? ::finalize %)
                                    (ptk/type? ::initialize %))
                               stream)
            work*   (volatile! nil)]
        (->> (rx/merge
              (->> stream
                   (rx/filter (ptk/type? ::add-work))
                   (rx/tap (fn [event]
                             (vswap! work* #(update (or % (new-work)) :total + (deref event)))))
                   (rx/ignore))
              (->> stream
                   (rx/filter (ptk/type? ::work-done))
                   (rx/tap (fn [event]
                             (vswap! work* #(some-> % (update :done + (deref event))))))
                   (rx/ignore))
              (->> stream
                   (rx/filter (ptk/type? ::show))
                   (rx/tap (fn [_]
                             (vswap! work* #(assoc (or % (new-work)) :shown? true))))
                   (rx/ignore))
              (->> (rx/interval tick-ms)
                   (rx/mapcat #(or (tick work*) (rx/empty)))))
             (rx/take-until stopper))))))

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state ::progress))))
