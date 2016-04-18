;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.dashboard
  (:require [beicon.core :as rx]
            [uuid.core :as uuid]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.repo :as rp]
            [uxbox.data.projects :as dp]
            [uxbox.data.colors :as dc]
            [uxbox.util.data :refer (deep-merge)]))

;; --- Events

(defn- setup-dashboard-state
  [state section]
  (update state :dashboard assoc
          :section section
          :collection-type :builtin
          :collection-id 1))

(defrecord InitializeDashboard [section]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [state (setup-dashboard-state state section)]
      (if (seq (:projects-by-id state))
        state
        (assoc state :loader true))))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [projects (seq (:projects-by-id state))
          color-collections (seq (:colors-by-id state))]
      (rx/merge
       ;; Load projects if needed
       (if projects
         (rx/empty)
         (rx/of (dp/fetch-projects)))

       (rx/of (dc/fetch-color-collections))

       (when (:loader state)
         (if projects
           (rx/of #(assoc % :loader false))
           (->> (rx/filter dp/projects-fetched? s)
                (rx/take 1)
                (rx/delay 1000)
                (rx/map (fn [_] #(assoc % :loader false))))))))))

(defn initialize
  [section]
  (InitializeDashboard. section))

(defn set-collection-type
  [type]
  {:pre [(contains? #{:builtin :own} type)]}
  (letfn [(select-first [state]
            (if (= type :builtin)
              (assoc-in state [:dashboard :collection-id] 1)
              (let [colls (sort-by :id (vals (:colors-by-id state)))]
                (assoc-in state [:dashboard :collection-id] (:id (first colls))))))]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (as-> state $
          (assoc-in $ [:dashboard :collection-type] type)
          (select-first $))))))

(defn set-collection
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc-in state [:dashboard :collection-id] id))))
