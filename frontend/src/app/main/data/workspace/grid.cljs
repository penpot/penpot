;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.grid
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.data.workspace.common :as dwc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private default-square-params
  {:size 16
   :color {:value "#59B9E2"
           :opacity 0.2}})

(defonce ^:private default-layout-params
  {:size 12
   :type :stretch
   :item-length nil
   :gutter 8
   :margin 0
   :color {:value "#DE4762"
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
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            data    (get-in state [:workspace-data page-id])
            params  (or (get-in data [:options :saved-grids :square])
                        (:square default-grid-params))
            grid    {:type :square
                     :params params
                     :display true}]
        (rx/of (dwc/update-shapes [frame-id]
                                  (fn [obj] (update obj :grids (fnil #(conj % grid) [])))))))))


(defn remove-frame-grid
  [frame-id index]
  (ptk/reify ::set-frame-grid
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwc/update-shapes [frame-id] (fn [o] (update o :grids (fnil #(d/remove-at-index % index) []))))))))

(defn set-frame-grid
  [frame-id index data]
  (ptk/reify ::set-frame-grid
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwc/update-shapes [frame-id] #(assoc-in % [:grids index] data))))))

(defn set-default-grid
  [type params]
  (ptk/reify ::set-default-grid
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (:current-page-id state)
            prev-value (get-in state [:workspace-data :pages-index pid :options :saved-grids type])]
        (rx/of (dwc/commit-changes [{:type :set-option
                                     :page-id pid
                                     :option [:saved-grids type]
                                     :value params}]
                                   [{:type :set-option
                                     :page-id pid
                                     :option [:saved-grids type]
                                     :value prev-value}]
                                   {:commit-local? true}))))))
