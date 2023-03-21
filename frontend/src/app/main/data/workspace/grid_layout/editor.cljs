;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.grid-layout.editor
  (:require
   [potok.core :as ptk]))

(defn hover-grid-cell
  [grid-id row column add-to-set]
  (ptk/reify ::hover-grid-cell
    ptk/UpdateEvent
    (update [_ state]
      (update-in
       state
       [:workspace-grid-edition grid-id :hover]
       (fn [hover-set]
         (let [hover-set (or hover-set #{})]
           (if add-to-set
             (conj hover-set [row column])
             (disj hover-set [row column]))))))))

(defn select-grid-cell
  [grid-id row column]
  (ptk/reify ::select-grid-cell
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-grid-edition grid-id :selected] [row column]))))

(defn remove-selection
  [grid-id]
  (ptk/reify ::remove-selection
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-grid-edition grid-id] dissoc :selected))))

(defn stop-grid-layout-editing
  [grid-id]
  (ptk/reify ::stop-grid-layout-editing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-grid-edition dissoc grid-id))))
