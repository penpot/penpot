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
  "Generates changes to update the new content of the shape"
  [it objects page-id shape old-content new-content]

  (assert (path/content? old-content))
  (assert (path/content? new-content))

  (let [shape-id (:id shape)

        ;; We set the old values so the update-shapes works
        objects
        (update objects shape-id
                (fn [shape]
                  (-> shape
                      (assoc :content old-content)
                      (path/update-geometry))))

        changes
        (-> (pcb/empty-changes it page-id)
            (pcb/with-objects objects))

        new-content
        (path/content new-content)]

    (cond
      ;; https://tree.taiga.io/project/penpot/issue/2366
      (nil? shape-id)
      changes

      (empty? new-content)
      (-> changes
          (pcb/remove-objects [shape-id])
          (pcb/resize-parents [shape-id]))

      :else
      (-> changes
          (pcb/update-shapes [shape-id]
                             (fn [shape]
                               (-> shape
                                   (assoc :content new-content)
                                   (path/update-geometry))))
          (pcb/resize-parents [shape-id])))))

(defn save-path-content
  ([]
   (save-path-content {}))
  ([{:keys [preserve-move-to] :or {preserve-move-to false}}]
   (ptk/reify ::save-path-content
     ptk/UpdateEvent
     (update [_ state]
       (let [content (st/get-path state :content)
             content (if (and (not preserve-move-to)
                              (= (-> content last :command) :move-to))
                       (into [] (take (dec (count content)) content))
                       content)]
         (-> state
             (st/set-content content))))

     ptk/WatchEvent
     (watch [it state _]
       (let [page-id     (:current-page-id state)
             objects     (dsh/lookup-page-objects state page-id)
             id          (dm/get-in state [:workspace-local :edition])
             old-content (dm/get-in state [:workspace-local :edit-path id :old-content])
             shape       (st/get-path state)]

         (if (and (some? old-content) (some? (:id shape)))
           (let [changes (generate-path-changes it objects page-id shape old-content (:content shape))]
             (rx/of (dch/commit-changes changes)))
           (rx/empty)))))))


