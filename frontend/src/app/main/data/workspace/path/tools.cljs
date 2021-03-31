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
