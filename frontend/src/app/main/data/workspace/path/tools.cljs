;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.tools
  (:require
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.state :as st]
   [app.util.geom.path :as ugp]
   [app.common.geom.point :as gpt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn make-corner []
  (ptk/reify ::make-corner
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (st/get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (st/get-path state))
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            new-content (reduce ugp/make-corner-point (:content shape) selected-points)
            [rch uch] (changes/generate-path-changes page-id shape (:content shape) new-content)]
        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(defn make-curve []
  (ptk/reify ::make-curve
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (st/get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (st/get-path state))
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            new-content (reduce ugp/make-curve-point (:content shape) selected-points)
            [rch uch] (changes/generate-path-changes page-id shape (:content shape) new-content)]
        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(defn split-segments [[start end cmd]]
  (case (:command cmd)
    :line-to [cmd (ugp/split-line-to start cmd 0.5)]
    :curve-to [cmd (ugp/split-curve-to start cmd 0.5)]
    :close-path [cmd [(ugp/make-line-to (gpt/line-val start end 0.5))
                      cmd]]
    nil))

(defn add-node []
  (ptk/reify ::add-node
    ptk/WatchEvent
    (watch [_ state stream]

      (let [id (st/get-path-id state)
            page-id (:current-page-id state)
            shape (get-in state (st/get-path state))
            selected-points (get-in state [:workspace-local :edit-path id :selected-points] #{})
            content (:content shape)


            cmd-changes (->> (ugp/get-segments content selected-points)
                             (into {}
                                   (comp (map split-segments)
                                         (filter (comp not nil?)))))

            process-segments (fn [command]
                               (if (contains? cmd-changes command)
                                 (get cmd-changes command)
                                 [command]))

            new-content (into [] (mapcat process-segments) content)

            [rch uch] (changes/generate-path-changes page-id shape (:content shape) new-content)]
        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(defn remove-node []
  (ptk/reify ::remove-node))

(defn merge-nodes []
  (ptk/reify ::merge-nodes))

(defn join-nodes []
  (ptk/reify ::join-nodes))

(defn separate-nodes []
  (ptk/reify ::separate-nodes))

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))
