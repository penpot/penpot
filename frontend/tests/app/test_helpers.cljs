(ns app.test-helpers
  (:require [cljs.test :as t :include-macros true]
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


;; ---- Helpers to manage pages and objects

(def current-file-id (uuid/next))

(def initial-state
  {:current-file-id current-file-id
   :current-page-id nil
   :workspace-local dw/workspace-local-default
   :workspace-data {:id current-file-id
                    :components {}
                    :pages []
                    :pages-index {}}
   :workspace-libraries {}})

(defn current-page
  [state]
  (let [page-id (:current-page-id state)]
    (get-in state [:workspace-data :pages-index page-id])))

(defn sample-page
  ([state] (sample-page state {}))
  ([state {:keys [id name] :as props
           :or {id (uuid/next)
                name "page1"}}]
   (-> state
       (assoc :current-page-id id)
       (update :workspace-data
               cp/process-changes
               [{:type :add-page
                 :id id
                 :name name}]))))

(defn sample-shape
  ([state type] (sample-shape state type {}))
  ([state type props]
   (let [page  (current-page state)
         frame (cph/get-top-frame (:objects page))
         shape (-> (cp/make-minimal-shape type)
                   (gsh/setup {:x 0 :y 0 :width 1 :height 1})
                   (merge props))]
     (update state :workspace-data
             cp/process-changes
             [{:type :add-obj
               :id (:id shape)
               :page-id (:id page)
               :frame-id (:id frame)
               :obj shape}]))))

