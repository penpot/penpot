;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.specialized-panel
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn interrupt? [e] (= e :interrupt))

(def clear-specialized-panel
  (ptk/reify ::clear-specialized-panel
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :specialized-panel))))

(defn open-specialized-panel
  ([type]
   (ptk/reify ::open-specialized-panel-1
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id         (:current-page-id state)
             objects         (wsh/lookup-page-objects state page-id)
             selected-ids    (wsh/lookup-selected state)
             selected-shapes (map (d/getf objects) selected-ids)]

         (rx/of (open-specialized-panel type selected-shapes))))))

  ([type shapes]
   (ptk/reify ::open-specialized-panel-2
     ptk/UpdateEvent
     (update [_ state]
       (assoc state :specialized-panel {:type type :shapes shapes}))
     ptk/WatchEvent
     (watch [_ _ stream]
       (->> stream
            (rx/filter interrupt?)
            (rx/take 1)
            (rx/map (constantly clear-specialized-panel)))))))
