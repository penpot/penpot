;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.changes
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.path :as path]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.path.state :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn generate-path-changes
  "Generates changes to update the new path-data of the shape"
  [it objects page-id shape old-path-data new-path-data]

  (assert (path/path-data? old-path-data))
  (assert (path/path-data? new-path-data))

  (let [shape-id (:id shape)

        ;; We set the old values so the update-shapes works
        objects
        (update objects shape-id
                (fn [shape]
                  (-> shape
                      (assoc :path-data old-path-data)
                      (path/update-geometry))))

        changes
        (-> (pcb/empty-changes it page-id)
            (pcb/with-objects objects))

        new-path-data
        (path/path-data new-path-data)]

    (cond
      ;; https://tree.taiga.io/project/penpot/issue/2366
      (nil? shape-id)
      changes

      (empty? new-path-data)
      (-> changes
          (pcb/remove-objects [shape-id])
          (pcb/resize-parents [shape-id]))

      :else
      (-> changes
          (pcb/update-shapes [shape-id]
                             (fn [shape]
                               (-> shape
                                   (assoc :path-data new-path-data)
                                   (path/update-geometry))))
          (pcb/resize-parents [shape-id])))))

(defn save-path-data
  ([]
   (save-path-data {}))
  ([{:keys [preserve-move-to] :or {preserve-move-to false}}]
   (ptk/reify ::save-path-data
     ptk/UpdateEvent
     (update [_ state]
       (let [path-data (st/get-path state :path-data)
             path-data (if (and (not preserve-move-to)
                                (= (-> path-data last :command) :move-to))
                         (path/path-data (take (dec (count path-data)) path-data))
                         (path/path-data path-data))]
         (st/set-path-data state path-data)))

     ptk/WatchEvent
     (watch [it state _]
       (let [page-id     (:current-page-id state)
             local       (get state :workspace-local)
             id          (get local :edition)
             objects     (dsh/lookup-page-objects state page-id)]

         ;; NOTE: we proceed only if the shape is present on the
         ;; objects, if shape is a ephimeral drawing shape, we should
         ;; do nothing
         (when-let [shape (get objects id)]
           (when-let [old-path-data (dm/get-in local [:edit-path id :old-content])]
             (let [new-path-data (get shape :path-data)
                   changes       (generate-path-changes it objects page-id shape old-path-data new-path-data)]
               (rx/of (dch/commit-changes changes))))))))))
