;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.events
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [beicon.v2.core :as rx]
   [cljs.test :as t]
   [potok.v2.core :as ptk]))

;; ---- Helpers to manage global events

(defn on-error
  [cause]

  (js/console.log "STORE ERROR" (.-stack cause))
  (when-let [data (some-> cause ex-data ::sm/explain)]
    (pp/pprint (sm/humanize-explain data))))

(defn prepare-store
  "Create a store with the given initial state. Wait until a :the/end
  event occurs, and then call the function with the final state at
  this point."
  [state done completed-cb]
  (let [store  (ptk/store {:state state :on-error on-error})
        stream (ptk/input-stream store)
        stream (->> stream
                    (rx/take-until (rx/filter #(= :the/end %) stream))
                    (rx/last)
                    (rx/tap (fn []
                              (completed-cb @store)))
                    (rx/subs! (fn [_] (done))
                              (fn [cause]
                                (js/console.log "[error]:" cause))
                              (fn [_]
                                (js/console.log "[complete]"))))]
    store))

;; Remove definitely when we ensure that the above method works
;; well in more advanced tests.
#_(defn do-update
    "Execute an update event and returns the new state."
    [event state]
    (ptk/update event state))

#_(defn do-watch
    "Execute a watch event and return an observable, that
   emits once a list with all new events."
    [event state]
    (->> (ptk/watch event state nil)
         (rx/reduce conj [])))

#_(defn do-watch-update
    "Execute a watch event and return an observable, that
  emits once the new state, after all new events applied
  in sequence (considering they are all update events)."
    [event state]
    (->> (do-watch event state)
         (rx/map (fn [new-events]
                   (reduce
                    (fn [new-state new-event]
                      (do-update new-event new-state))
                    state
                    new-events)))))

