;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.collapse
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [potok.v2.core :as ptk]))

;; --- Shape attrs (Layers Sidebar)

(defn expand-all-parents
  [ids objects]
  (ptk/reify ::expand-all-parents
    ptk/UpdateEvent
    (update [_ state]
      (let [expand-fn (fn [expanded]
                        (let [parents-seqs (map (fn [x] (cfh/get-parent-ids objects x)) ids)
                              flat-parents (apply concat parents-seqs)
                              non-root-parents (remove #(= % uuid/zero) flat-parents)
                              distinct-parents (into #{} non-root-parents)]
                          (merge expanded
                                 (into {}
                                       (map (fn [id] {id true}) distinct-parents)))))]
        (update-in state [:workspace-local :expanded] expand-fn)))))


(defn toggle-collapse
  [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :expanded id] not))))

(defn expand-collapse
  [id]
  (ptk/reify ::expand-collapse
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :expanded id] true))))

(defn collapse-all
  []
  (ptk/reify ::collapse-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :expanded))))

