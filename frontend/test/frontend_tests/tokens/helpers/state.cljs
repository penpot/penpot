;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.tokens.helpers.state
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.style-dictionary :as sd]
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
  []
  (ptk/reify ::end+
    ptk/WatchEvent
    (watch [_ state _]
      (let [data (dsh/lookup-file-data state)]
        (->> (cfo/get-tokens-in-active-sets
              (cfo/get-tokens-status data)
              (cfo/get-tokens-lib data))
             (sd/resolve-tokens)
             (rx/mapcat #(rx/of (end))))))))

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
              (fn [{:keys [save-undo? undo-changes redo-changes undo-group tags stack-undo? selected-before]}]
                (if (and save-undo? (seq undo-changes))
                  (rx/of (dwu/append-undo
                          {:undo-changes undo-changes
                           :redo-changes redo-changes
                           :undo-group undo-group
                           :tags tags
                           :selected-before selected-before}
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
