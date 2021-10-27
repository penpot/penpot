;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.shapes-to-path
  (:require
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as cb]
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
                  (mapcat #(cp/get-children % objects))
                  selected)

            changes
            (-> (cb/empty-changes it page-id)
                (cb/with-objects objects)
                (cb/remove-objects children-ids)
                (cb/update-shapes selected #(upsp/convert-to-path % objects)))]

        (rx/of (dch/commit-changes changes))))))
