;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.grid
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.changes-builder :as pcb]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private default-square-params
  {:size 16
   :color {:color clr/info
           :opacity 0.4}})

(defonce ^:private default-layout-params
  {:size 12
   :type :stretch
   :item-length nil
   :gutter 8
   :margin 0
   :color {:color clr/default-layout
           :opacity 0.1}})

(defonce default-grid-params
  {:square default-square-params
   :column default-layout-params
   :row    default-layout-params})

(defn add-frame-grid
  [frame-id]
  (dm/assert! (uuid? frame-id))
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
      (let [page (wsh/lookup-page state)]
        (rx/of (dch/commit-changes
                 (-> (pcb/empty-changes it)
                     (pcb/with-page page)
                     (pcb/set-page-option [:saved-grids type] params))))))))
