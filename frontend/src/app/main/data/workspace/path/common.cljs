;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.common
  (:require
   [app.main.data.workspace.path.state :as st]
   [potok.core :as ptk]))

(defn init-path []
  (ptk/reify ::init-path))

(defn clean-edit-state
  [state]
  (dissoc state :last-point :prev-handler :drag-handler :preview))

(defn finish-path
  [_source]
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (update-in [:workspace-local :edit-path id] clean-edit-state))))))
