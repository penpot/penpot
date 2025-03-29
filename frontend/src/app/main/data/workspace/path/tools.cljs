;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.tools
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.path.segment :as path.segm]
   [app.common.types.path.shape-to-path :as upsp]
   [app.common.types.path.subpath :as path.subp]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.features :as features]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn process-path-tool
  "Generic function that executes path transformations with the content and selected nodes"
  ([tool-fn]
   (process-path-tool nil tool-fn))
  ([points tool-fn]
   (ptk/reify ::process-path-tool
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id (get state :current-page-id)
             objects (dsh/lookup-page-objects state page-id)

             ;; FIXME: repeated operation?
             shape   (st/get-path state)
             id      (st/get-path-id state)

             selected-points
             (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})

             features
             (features/get-team-enabled-features state)

             points
             (or points selected-points)]

         (when (and (seq points) (some? shape))
           (let [new-content
                 (-> (tool-fn (:content shape) points)
                     (path.subp/close-subpaths))

                 changes
                 (changes/generate-path-changes it features objects page-id shape (:content shape) new-content)]

             (rx/concat
              (if (cfh/path-shape? shape)
                (rx/empty)
                (rx/of (dwsh/update-shapes [id] upsp/convert-to-path)))
              (rx/of (dch/commit-changes changes)
                     (when (empty? new-content)
                       (dwe/clear-edition-mode)))))))))))

(defn make-corner
  ([]
   (make-corner nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce path.segm/make-corner-point content points)))))

(defn make-curve
  ([]
   (make-curve nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce path.segm/make-curve-point content points)))))

(defn add-node []
  (process-path-tool (fn [content points] (path.segm/split-segments content points 0.5))))

(defn remove-node []
  (process-path-tool path.segm/remove-nodes))

(defn merge-nodes []
  (process-path-tool path.segm/merge-nodes))

(defn join-nodes []
  (process-path-tool path.segm/join-nodes))

(defn separate-nodes []
  (process-path-tool path.segm/separate-nodes))

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))
