;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.typography
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn set-base-font-size [base-font-size]
  (ptk/reify ::set-base-font-size
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id (dm/get-in state [:workspace :current-file-id])
            file-data (dm/get-in state [:files file-id :data])
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/set-base-font-size base-font-size))]
        (rx/of
         (dch/commit-changes changes))))))