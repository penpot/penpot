;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.events
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [beicon.core :as rx]
   [cljs.pprint :refer [pprint]]
   [cljs.test :as t :include-macros true]
   [potok.core :as ptk]))

;; ---- Helpers to manage global events


(defn prepare-store
  "Create a store with the given initial state. Wait until
   a :the/end event occurs, and then call the function with
   the final state at this point."
 [state done completed-cb]
 (let [store (ptk/store {:state state})
       stream (ptk/input-stream store)
       stream (->> stream
                   (rx/take-until (rx/filter #(= :the/end %) stream))
                   (rx/last)
                   (rx/do
                     (fn []
                       (completed-cb @store)))
                   (rx/subs done #(throw %)))]
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

