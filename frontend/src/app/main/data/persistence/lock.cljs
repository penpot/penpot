;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence.lock
  "Read-only lock of the workspace while saves fail."
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.edition :as dwe]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn failing?
  "True while saves fail: the queue is halted, or an attempt of the slow
  cycle is in flight (status :saving until it lands or fails again)."
  [pstate]
  (or (= :error (:status pstate))
      (true? (:recovering pstate))))

(defn locked?
  "True while the workspace is read-only because saves fail."
  [state]
  (some? (dm/get-in state [:workspace-global :persistence-lock])))

(defn cancel-gestures?
  "True for the event that cancels the gestures in progress when saves
  start failing; it comes before the `:interrupt` that stops them, so a
  drag puts its shapes back instead of committing its partial move."
  [event]
  (ptk/type? ::cancel-gestures event))

(defn gesture-stopper
  "Stops a drag as `mse/drag-stopper` does, and also when the gestures in
  progress are cancelled because saves fail; `cancelled?` turns true
  before that stop reaches the drag. A drag that sees it ends without
  committing anything."
  ([stream cancelled?]
   (gesture-stopper stream cancelled? nil))
  ([stream cancelled? opts]
   (rx/merge (mse/drag-stopper stream opts)
             (->> stream
                  (rx/filter cancel-gestures?)
                  (rx/tap (fn [_] (vreset! cancelled? true)))))))

(defn- lock-on-failure
  "Puts the workspace in read-only mode and keeps the read-only value it
  had, which `unlock-after-failure` brings back. Gestures in progress are
  cancelled, so they commit nothing."
  []
  (ptk/reify ::lock-on-failure
    ptk/UpdateEvent
    (update [_ state]
      (if (dm/get-in state [:workspace-global :persistence-lock])
        state
        (update state :workspace-global
                (fn [wglobal]
                  (assoc wglobal
                         :persistence-lock {:read-only? (true? (:read-only? wglobal))}
                         :read-only? true)))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event ::cancel-gestures)
             :interrupt
             (dwe/clear-edition-mode)))))

(defn- unlock-after-failure
  "Brings back the read-only value the workspace had before saves failed.
  While the render context is lost the workspace stays read-only and the
  value goes to the one `context-restored` brings back."
  []
  (ptk/reify ::unlock-after-failure
    ptk/UpdateEvent
    (update [_ state]
      (if-let [lock (dm/get-in state [:workspace-global :persistence-lock])]
        (let [read-only? (:read-only? lock)]
          (if (dm/get-in state [:render-state :lost])
            (-> state
                (update :workspace-global dissoc :persistence-lock)
                (assoc-in [:render-state :pre-context-lost-read-only?] read-only?))
            (update state :workspace-global
                    (fn [wglobal]
                      (-> wglobal
                          (dissoc :persistence-lock)
                          (assoc :read-only? read-only?))))))
        state))))

(defn sync-failure-lock
  "Locks the workspace when saves start failing and unlocks it when they
  recover. A user who cannot edit is never locked: the workspace is
  already read-only for them, and the failure toast says why."
  []
  (ptk/reify ::sync-failure-lock
    ptk/WatchEvent
    (watch [_ state _]
      (let [lock?   (and (failing? (:persistence state))
                         (true? (dm/get-in state [:permissions :can-edit])))
            locked? (some? (dm/get-in state [:workspace-global :persistence-lock]))]
        (cond
          (and lock? (not locked?)) (rx/of (lock-on-failure))
          (and locked? (not lock?)) (rx/of (unlock-after-failure))
          :else                     (rx/empty))))))
