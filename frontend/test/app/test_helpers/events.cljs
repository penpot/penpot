(ns app.test-helpers.events
  (:require
   [cljs.test :as t :include-macros true]
   [cljs.pprint :refer [pprint]]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.uuid :as uuid]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace :as dw]))

;; ---- Helpers to manage global events

(defn do-update
  "Execute an update event and returns the new state."
  [event state]
  (ptk/update event state))

(defn do-watch
  "Execute a watch event and return an observable, that
   emits once a list with all new events."
  [event state]
  (->> (ptk/watch event state nil)
       (rx/reduce conj [])))

(defn do-watch-update
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

