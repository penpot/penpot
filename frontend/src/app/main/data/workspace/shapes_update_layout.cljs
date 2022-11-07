;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shapes-update-layout
  (:require
   [app.common.data :as d]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn update-layout-positions
  [ids]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            ids     (->> ids (filter (partial ctl/layout? objects)))]
        (if (d/not-empty? ids)
          (let [modif-tree (dwm/create-modif-tree ids (ctm/reflow-modifiers))]
            (rx/of (dwm/set-modifiers modif-tree)
                   (dwm/apply-modifiers)))
          (rx/empty))))))
