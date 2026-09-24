;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.scroll
  "Scroll position save/restore for sidebar panels.

  Panels unmount on every tab switch, so positions that must survive it
  cannot live in component state. The store is created once in
  `left-sidebar*` (which stays mounted while tabs swap, unlike
  `layers-content*` and the toolboxes) with `mf/use-var` and threaded to
  the panels as a prop. Shape: {[panel id] scroll-top}."
  (:require
   [app.util.dom :as dom]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

(def ^:private max-restore-attempts
  10)

(defn save-scroll!
  "Saves the scrollTop of the scroll event target into the store under k.
  Plain swap!, never triggers renders. Missing target is a no-op."
  [store* k event]
  (when-let [target (dom/get-target event)]
    (swap! store* assoc k (.-scrollTop target))))

(defn needs-restore-retry?
  "Pure decision for the restore loop: keep retrying while the content
  height is still changing (lazily rendered lists) and attempts remain."
  [prev-height cur-height attempts]
  (and (not= prev-height cur-height)
       (< attempts max-restore-attempts)))

(defn restore-scroll!
  [node saved]
  (set! (.-scrollTop node) saved))

(defn- restore-loop!
  [node saved prev-height attempts raf*]
  (restore-scroll! node saved)
  (let [height (.-scrollHeight node)]
    (when (needs-restore-retry? prev-height height attempts)
      (vreset! raf* (tm/raf #(restore-loop! node saved height (inc attempts) raf*))))))

(defn use-restore-scroll
  "Restores the scrollTop saved under [panel id] on mount and whenever
  panel or id change. Retries while the content height keeps changing so
  deep positions in lazily rendered lists are not clamped to the first
  chunk. Missing key or node is a silent no-op."
  [store* panel id node-ref]
  (mf/with-effect [panel id]
    (let [raf* (volatile! nil)]
      (when-let [node (mf/ref-val node-ref)]
        (when-let [saved (get @store* [panel id])]
          (restore-loop! node saved -1 0 raf*)))
      (fn []
        (when-some [raf @raf*]
          (tm/cancel-af! raf))))))
