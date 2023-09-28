;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.specialized-panel
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn interrupt? [e] (= e :interrupt))

(def clear-specialized-panel
  (ptk/reify ::clear-specialized-panel
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :specialized-panel))))

(defn open-specialized-panel
  [type shapes]
  (ptk/reify ::open-specialized-panel
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :specialized-panel {:type type :shapes shapes}))
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter interrupt?)
           (rx/take 1)
           (rx/map (constantly clear-specialized-panel))))))