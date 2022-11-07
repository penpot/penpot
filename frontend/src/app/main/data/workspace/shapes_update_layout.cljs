;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shapes-update-layout
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.transforms :as dwt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn update-layout-positions
  [ids]
  (ptk/reify ::update-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            ids     (->> ids (filter #(get-in objects [% :layout])))]
        (if (d/not-empty? ids)
          (rx/of (dwt/set-modifiers ids)
                 (dwt/apply-modifiers))
          (rx/empty))))))