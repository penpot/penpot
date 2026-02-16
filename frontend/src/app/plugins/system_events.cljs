;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.system-events
  (:require
   [app.main.data.event :as ev]
   [app.main.store :as st]))

;; Formats an event from the plugin system
(defn event
  [plugin-id name & {:as props}]
  (let [plugin-data (get-in @st/state [:profile :props :plugins :data plugin-id])]
    (-> props
        (assoc ::ev/name name)
        (assoc ::ev/origin "plugin")
        (assoc ::ev/context
               {:plugin-name (:name plugin-data)
                :plugin-url (:url plugin-data)})
        (ev/event))))

