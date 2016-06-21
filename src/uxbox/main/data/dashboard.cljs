;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.dashboard
  (:require [beicon.core :as rx]
            [uxbox.util.uuid :as uuid]
            [uxbox.common.rstore :as rs]
            [uxbox.common.router :as r]
            [uxbox.main.state :as st]
            [uxbox.common.schema :as sc]
            [uxbox.common.repo :as rp]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.colors :as dc]
            [uxbox.main.data.images :as di]
            [uxbox.util.data :refer (deep-merge)]))

;; --- Events

(defrecord InitializeDashboard [section]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update state :dashboard assoc
            :section section
            :collection-type :builtin
            :collection-id 1)))

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
