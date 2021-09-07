;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.grid
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.data.workspace.changes :as dch]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private default-square-params
  {:size 16
   :color {:color "#59B9E2"
           :opacity 0.4}})

(defonce ^:private default-layout-params
  {:size 12
   :type :stretch
   :item-length nil
   :gutter 8
   :margin 0
   :color {:color "#DE4762"
           :opacity 0.1}})

(defonce default-grid-params
  {:square default-square-params
   :column default-layout-params
   :row    default-layout-params})

(defn add-frame-grid
  [frame-id]
  (us/assert ::us/uuid frame-id)
  (ptk/reify ::add-frame-grid
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            data    (get-in state [:workspace-data :pages-index page-id])
            params  (or (get-in data [:options :saved-grids :square])
                        (:square default-grid-params))
            grid    {:type :square
                     :params params
                     :display true}]
        (rx/of (dch/update-shapes [frame-id]
                                  (fn [obj] (update obj :grids (fnil #(conj % grid) [])))))))))


(defn remove-frame-grid
  [frame-id index]
  (ptk/reify ::remove-frame-grid
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [frame-id] (fn [o] (update o :grids (fnil #(d/remove-at-index % index) []))))))))

(defn set-frame-grid
  [frame-id index data]
  (ptk/reify ::set-frame-grid
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dch/update-shapes [frame-id] #(assoc-in % [:grids index] data))))))

(defn set-default-grid
  [type params]
  (ptk/reify ::set-default-grid
    ptk/WatchEvent
    (watch [it state _]
      (let [pid (:current-page-id state)
            prev-value (get-in state [:workspace-data :pages-index pid :options :saved-grids type])]
        (rx/of (dch/commit-changes
                {:redo-changes [{:type :set-option
                                 :page-id pid
                                 :option [:saved-grids type]
                                 :value params}]
                 :undo-changes [{:type :set-option
                                 :page-id pid
                                 :option [:saved-grids type]
                                 :value prev-value}]
                 :origin it}))))))
