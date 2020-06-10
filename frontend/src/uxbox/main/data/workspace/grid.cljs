;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.grid
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace.common :as dwc]))

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

(defn add-frame-grid [frame-id]
  (ptk/reify ::set-frame-grid
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)
            default-params (or
                            (get-in state [:workspace-data pid :options :saved-grids :square])
                            (:square default-grid-params))
            prop-path [:workspace-data pid :objects frame-id :grids]
            grid {:type :square
                  :params default-params
                  :display true}]
        (-> state
            (update-in prop-path #(if (nil? %) [grid] (conj % grid))))))))

(defn remove-frame-grid [frame-id index]
  (ptk/reify ::set-frame-grid
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (-> state
            (update-in [:workspace-data pid :objects frame-id :grids] #(d/remove-at-index % index)))))))

(defn set-frame-grid [frame-id index data]
  (ptk/reify ::set-frame-grid
    dwc/IBatchedChange
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:current-page-id state)]
        (->
         state
         (assoc-in [:workspace-data pid :objects frame-id :grids index] data))))))

(defn set-default-grid [type params]
  (ptk/reify ::set-default-grid
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (:current-page-id state)
            prev-value (get-in state [:workspace-data pid :options :saved-grids type])]
        (rx/of (dwc/commit-changes [{:type :set-option
                                     :option [:saved-grids type]
                                     :value params}]
                                   [{:type :set-option
                                     :option [:saved-grids type]
                                     :value prev-value}]
                                   {:commit-local? true}))))))
