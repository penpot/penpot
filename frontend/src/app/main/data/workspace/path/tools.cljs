;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.tools
  (:require
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.state :as st]
   [app.util.path.tools :as upt]
   [app.util.path.subpaths :as ups]
   [app.common.geom.point :as gpt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn process-path-tool
  "Generic function that executes path transformations with the content and selected nodes"
  ([tool-fn]
   (process-path-tool nil tool-fn))
  ([points tool-fn]
   (ptk/reify ::process-path-tool
     ptk/WatchEvent
     (watch [_ state stream]
       (let [id (st/get-path-id state)
             page-id (:current-page-id state)
             shape (get-in state (st/get-path state))
             selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
             points (or points selected-points)
             new-content (-> (tool-fn (:content shape) points)
                             (ups/close-subpaths))
             [rch uch] (changes/generate-path-changes page-id shape (:content shape) new-content)]
         (rx/of (dch/commit-changes rch uch {:commit-local? true})))))))

(defn make-corner
  ([]
   (make-corner nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce upt/make-corner-point content points)))))

(defn make-curve
  ([]
   (make-curve nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce upt/make-curve-point content points)))))

(defn add-node []
  (process-path-tool (fn [content points] (upt/split-segments content points 0.5))))

(defn remove-node []
  (process-path-tool upt/remove-nodes))

(defn merge-nodes []
  (process-path-tool upt/merge-nodes))

(defn join-nodes []
  (process-path-tool upt/join-nodes))

(defn separate-nodes []
  (process-path-tool upt/separate-nodes))

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))
