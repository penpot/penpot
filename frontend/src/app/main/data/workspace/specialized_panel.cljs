;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.specialized-panel
  (:require
   [app.common.data :as d]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.common :as-alias dwc]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn interrupt? [e] (or (= e :interrupt) (= e ::interrupt)))

(defn clear-specialized-panel
  []
  (ptk/reify ::clear-specialized-panel
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :specialized-panel))))


(defn open-specialized-panel
  [type]
  (ptk/reify ::open-specialized-panel
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id         (:current-page-id state)
            objects         (dsh/lookup-page-objects state page-id)
            selected-ids    (dsh/lookup-selected state)
            selected-shapes (map (d/getf objects) selected-ids)]
        (assoc state :specialized-panel {:type type :shapes selected-shapes})))
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> (rx/merge
            (rx/filter interrupt? stream)
            (rx/filter (ptk/type? ::dwc/undo) stream))
           (rx/take 1)
           (rx/map clear-specialized-panel)))))
