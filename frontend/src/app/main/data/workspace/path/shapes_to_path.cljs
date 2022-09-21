;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.shapes-to-path
  (:require
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.path.shapes-to-path :as upsp]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn convert-selected-to-path []
  (ptk/reify ::convert-selected-to-path
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            objects  (wsh/lookup-page-objects state)
            selected (wsh/lookup-selected state)

            children-ids
            (into #{}
                  (mapcat #(cph/get-children-ids objects %))
                  selected)

            changes
            (-> (pcb/empty-changes it page-id)
                (pcb/with-objects objects)
                (pcb/update-shapes selected #(upsp/convert-to-path % objects))
                (pcb/remove-objects children-ids))]

        (rx/of (dch/commit-changes changes))))))
