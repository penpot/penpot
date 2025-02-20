;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.grid
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.grid :as ctg]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-frame-grid
  [frame-id]
  (dm/assert! (uuid? frame-id))
  (ptk/reify ::add-frame-grid
    ptk/WatchEvent
    (watch [_ state _]
      (let [page    (dsh/lookup-page state)
            params  (or (dm/get-in page [:default-grids :square])
                        (:square ctg/default-grid-params))
            grid    {:type :square
                     :params params
                     :display true}]
        (rx/of (dwsh/update-shapes [frame-id]
                                   (fn [obj] (update obj :grids (fnil #(conj % grid) [])))))))))


(defn remove-frame-grid
  [frame-id index]
  (ptk/reify ::remove-frame-grid
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes [frame-id] (fn [o] (update o :grids (fnil #(d/remove-at-index % index) []))))))))

(defn set-frame-grid
  [frame-id index data]
  (ptk/reify ::set-frame-grid
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes [frame-id] #(assoc-in % [:grids index] data))))))

(defn set-default-grid
  [type params]
  (ptk/reify ::set-default-grid
    ptk/WatchEvent
    (watch [it state _]
      (let [page (dsh/lookup-page state)]
        (rx/of (dch/commit-changes
                (-> (pcb/empty-changes it)
                    (pcb/with-page page)
                    (pcb/set-default-grid type params))))))))
