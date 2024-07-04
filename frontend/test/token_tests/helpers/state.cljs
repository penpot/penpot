(ns token-tests.helpers.state
  (:require
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn stop-on
  "Helper function to be used with async version of run-store.

  Will stop the execution after event with `event-type` has completed."
  [event-type]
  (fn [stream]
    (->> stream
         #_(rx/tap #(prn (ptk/type %)))
         (rx/filter #(ptk/type? event-type %)))))

(def stop-on-send-update-indices
  "Stops on `send-update-indices` function being called, which should be the last function of an event chain."
  (stop-on :app.main.data.workspace.changes/send-update-indices))

;; Support for async events in tests
;; https://chat.kaleidos.net/penpot-partners/pl/tz1yoes3w3fr9qanxqpuhoz3ch
(defn run-store
  "Async version of `frontend-tests.helpers.state/run-store`."
  ([store done events completed-cb]
   (run-store store done events completed-cb nil))
  ([store done events completed-cb stopper]
   (let [stream (ptk/input-stream store)]
     (->> stream
          (rx/take-until (if stopper
                           (stopper stream)
                           (rx/filter #(= :the/end %) stream)))
          (rx/last)
          (rx/tap (fn []
                    (completed-cb @store)))
          (rx/subs! (fn [_] (done))
                    (fn [cause]
                      (js/console.log "[error]:" cause))
                    (fn [_]
                      #_(js/console.log "[complete]"))))
     (doall (for [event events]
              (ptk/emit! store event)))
     (ptk/emit! store :the/end))))

(defn run-store-async
  "Helper version of `run-store` that automatically stops on the `send-update-indices` event"
  ([store done events completed-cb]
   (run-store store done events completed-cb stop-on-send-update-indices))
  ([store done events completed-cb stop-on]
   (run-store store done events completed-cb stop-on)))
