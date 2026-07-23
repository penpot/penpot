;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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

(defn- drop-trailing-move-to
  "Drops a trailing subpath start without segments."
  [content]
  (if (= :move-to (-> content last :command))
    (path/content (take (dec (count content)) content))
    content))

(defn- update-object-content
  [state f]
  (let [location (st/get-path-location state)
        object   (get-in state location)
        content  (some-> (:content object) f)]
    (cond-> state
      (some? content)
      (assoc-in location (cond-> (assoc object :content content)
                           (seq content) (path/update-geometry))))))

(defn finish-path
  []
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (update-in [:workspace-local :edit-path id] clean-edit-state)
            (update-object-content (comp path/close-subpaths drop-trailing-move-to)))))))

(defn cancel-pending-segment
  "Cancels the pending segment without leaving draw mode."
  []
  (ptk/reify ::cancel-pending-segment
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (-> state
            (update-in [:workspace-local :edit-path id] clean-edit-state)
            (update-object-content drop-trailing-move-to))))))
