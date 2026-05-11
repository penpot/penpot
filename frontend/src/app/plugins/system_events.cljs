;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.system-events
  (:require
   [app.main.data.event :as ev]
   [app.main.store :as st]
   [app.plugins.register :as r]))

;; Formats an event from the plugin system
(defn event
  [plugin-id name & {:as props}]
  (if (= plugin-id r/mcp-plugin-id)
    (-> props
        (assoc ::ev/name name)
        (assoc ::ev/origin "mcp")
        (ev/event))

    (let [plugin-data (r/get-plugin-data @st/state plugin-id)]
      (-> props
          (assoc ::ev/name name)
          (assoc ::ev/origin "plugin")
          (assoc ::ev/context
                 {:plugin-name (:name plugin-data)
                  :plugin-url (:url plugin-data)})
          (ev/event)))))

(defn add-event
  [event plugin-id]
  (let [plugin-data (r/get-plugin-data @st/state plugin-id)]
    (with-meta
      event
      (if (= plugin-id r/mcp-plugin-id)
        {::ev/origin "mcp"}
        {::ev/origin "plugin"
         ::ev/context {:plugin-name (:name plugin-data)
                       :plugin-url (:url plugin-data)}}))))
