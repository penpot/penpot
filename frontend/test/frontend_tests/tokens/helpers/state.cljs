;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.helpers.state
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn end
  "Apply `attributes` that match `token` for `shape-ids`.

  Optionally remove attributes from `attributes-to-remove`,
  this is useful for applying a single attribute from an attributes set
  while removing other applied tokens from this set."
  []
  (ptk/reify ::end
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/empty))))

(defn end+
  "Emits `end` once the running token propagations have finished and the
  active tokens are resolved."
  []
  (ptk/reify ::end+
    ptk/WatchEvent
    (watch [_ state stream]
      (let [data          (dsh/lookup-file-data state)
            tokens-status (cfo/get-tokens-status data)
            tokens-lib    (cfo/get-tokens-lib data)
            running       (dwtp/running-propagations state)]
        (rx/concat
         (if (seq running)
           (->> stream
                (rx/filter (ptk/type? ::dwtp/propagation-finished))
                (rx/filter #(contains? running (deref %)))
                (rx/take (count running))
                (rx/ignore))
           (rx/empty))
         (if (and tokens-status tokens-lib)
           (->> (cfo/get-tokens-in-active-sets tokens-status tokens-lib)
                (sd/resolve-tokens)
                (rx/mapcat #(rx/of (end))))
           (rx/of (end))))))))

(defn stop-on
  "Helper function to be used with async version of run-store.

  Will stop the execution after event with `event-type` has completed."
  [event-type]
  (fn [stream]
    (->> stream
         #_(rx/tap #(prn (ptk/type %)))
         (rx/filter #(ptk/type? event-type %))
         ;; Safeguard timeout
         (rx/timeout 200 (rx/of :the/end)))))

(defn stop-on-propagations-done
  "Stopper of `run-store` once the last of the token propagations queued
  in `store` finishes."
  [store]
  (fn [stream]
    (->> stream
         (rx/filter (ptk/type? ::dwtp/propagation-finished))
         ;; Checked once the event has updated the queue
         (rx/delay 0)
         (rx/filter #(empty? (dwtp/running-propagations @store))))))

(defn stop-on-undo-commit
  "Stops after the first `commit-undo-transaction`, the last event of a
  tokens propagation. Use it when the propagation resolves tokens more
  than once and outlasts `end+`."
  [stream]
  (rx/filter (ptk/type? ::dwu/commit-undo-transaction) stream))

(def stop-on-send-update-indices
  "Stops on `send-update-indices` function being called, which should be the last function of an event chain."
  (stop-on ::end))

(defn watch-undo-stack
  "Maintain the workspace undo stack by watching for commit events.
   Must be emitted before the events under test so undo entries are populated."
  []
  (ptk/reify ::watch-undo-stack
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper-s (->> stream (rx/filter (ptk/type? ::watch-undo-stack)))]
        (->> stream
             (rx/filter dch/commit?)
             (rx/map deref)
             (rx/filter #(= :local (:source %)))
             (rx/mapcat
              (fn [{:keys [save-undo? undo-changes redo-changes undo-group tags stack-undo? selected-before translation?]}]
                (if (and save-undo? (seq undo-changes))
                  (rx/of (dwu/append-undo
                          {:undo-changes undo-changes
                           :redo-changes redo-changes
                           :undo-group undo-group
                           :tags tags
                           :selected-before selected-before
                           :translation (boolean translation?)}
                          stack-undo?))
                  (rx/empty))))
             (rx/take-until stopper-s))))))

;; Support for async events in tests
;; https://chat.kaleidos.net/penpot-partners/pl/tz1yoes3w3fr9qanxqpuhoz3ch
(defn run-store
  "Async version of `frontend-tests.helpers.state/run-store`."
  ([store done events completed-cb]
   (run-store store done events completed-cb nil))
  ([store done events completed-cb stopper]
   (let [stream (ptk/input-stream store)
         stopper-s (if (fn? stopper)
                     (stopper stream)
                     (rx/filter #(= :the/end %) stream))]
     (->> stream
          (rx/take-until stopper-s)
          (rx/last)
          (rx/tap (fn [_]
                    (completed-cb @store)))
          (rx/subs! (fn [_] (done))
                    (fn [cause]
                      (js/console.log "[error]:" cause))
                    (fn [_]
                      #_(js/console.log "[complete]"))))
     (doseq [event (concat events [(end+)])]
       (ptk/emit! store event))
     (ptk/emit! store :the/end))))

(defn run-store-async
  "Helper version of `run-store` that automatically stops on the `send-update-indices` event"
  ([store done events completed-cb]
   (run-store store done events completed-cb stop-on-send-update-indices))
  ([store done events completed-cb stop-on]
   (run-store store done events completed-cb stop-on)))

(def ^:private run-timeout-ms
  "Longest run of `run-store*` before it fails."
  10000)

(defn run-store*
  "Promise version of `run-store`: emits `events` and `end+` on `store` and
  resolves with its state once `stopper`, a function of the input stream,
  emits (or right after the events without it). Rejects when the stream
  fails or the stopper doesn't emit within `run-timeout-ms`."
  ([store events]
   (run-store* store events nil))
  ([store events stopper]
   (js/Promise.
    (fn [resolve reject]
      (let [stream    (ptk/input-stream store)
            stopper-s (if (fn? stopper)
                        (stopper stream)
                        (rx/filter #(= :the/end %) stream))]
        (->> stream
             (rx/take-until stopper-s)
             (rx/ignore)
             (rx/timeout run-timeout-ms)
             (rx/subs! (fn [_])
                       reject
                       #(resolve @store)))
        (doseq [event (concat events [(end+)])]
          (ptk/emit! store event))
        (ptk/emit! store :the/end))))))

(defn watch-commits
  "Atom with the commits that go through `store`, in order."
  [store]
  (let [commits (atom [])]
    (rx/subs! #(swap! commits conj (deref %))
              (rx/filter dch/commit? (ptk/input-stream store)))
    commits))
