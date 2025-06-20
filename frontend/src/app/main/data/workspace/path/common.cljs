;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.common
  (:require
   [app.common.types.path :as path]
   [app.main.data.workspace.path.state :as st]
   [potok.v2.core :as ptk]))

(defn init-path []
  (ptk/data-event ::init-path {}))

(defn clean-edit-state
  [state]
  (dissoc state :last-point :prev-handler :drag-handler :preview))

(defn finish-path
  []
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (update-in [:workspace-local :edit-path id] clean-edit-state)
            (update-in (st/get-path-location state :content) path/close-subpaths))))))
