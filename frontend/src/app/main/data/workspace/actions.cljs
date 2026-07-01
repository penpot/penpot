;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.actions
  "Actions management for the History panel's ACTIONS tab.

   Tracks changes made by all users (not just undo entries for the
   current user). This data is fetched from the server on workspace
   init and augmented with real-time notifications as remote changes
   arrive."
  (:require
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Default state for :workspace-actions
(defonce default-state
  {:status :loading
   :data []
   :profiles #{}})

(declare fetch-actions)

(defn init-actions-state
  "Initialize the actions state and trigger an initial fetch."
  []
  (ptk/reify ::init-actions-state
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-actions default-state))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-actions)))))

(defn update-actions-state
  "Merge partial state into :workspace-actions."
  [actions-state]
  (ptk/reify ::update-actions-state
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-actions merge actions-state))))

(defn fetch-actions
  "Fetch recent file actions from the server for the current file."
  []
  (ptk/reify ::fetch-actions
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-file-actions {:file-id file-id :limit 100})
             (rx/map #(update-actions-state {:status :loaded :data %})))))))

(defn add-remote-action
  "Add a remote action entry received via real-time notification.
   `changes` is the vector of changes, `profile-id` identifies the
   author, and `timestamp` is when the change was made."
  [profile-id timestamp changes]
  (ptk/reify ::add-remote-action
    ptk/UpdateEvent
    (update [_ state]
      (let [entry {:profile-id profile-id
                     :created-at timestamp
                     :changes    (vec changes)
                     :id         (uuid/next)}]
        (update-in state [:workspace-actions :data]
                   (fn [data]
                     ;; Prepend new entry and keep at most 200 entries
                     (let [data (into [entry] data)]
                       (if (> (count data) 200)
                         (subvec data 0 200)
                         data))))))))
