;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins.system-events
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.event :as ev]
   [app.main.store :as st]
   [app.plugins.register :as r]))

(defn- get-origin
  [plugin-id]
  (if (= plugin-id r/mcp-plugin-id) "mcp" "plugin"))

(defn- get-context
  "Returns the audit context of an action run by a plugin: the file it ran
  in (none on the dashboard) and, for the MCP, the session of the tab that
  ran it. Events drop the nil values."
  [state plugin-id]
  (let [file-id (:current-file-id state)]
    (if (= plugin-id r/mcp-plugin-id)
      {:file-id file-id
       :mcp-session-id (dm/get-in state [:mcp :session-id])}
      (let [plugin-data (r/get-plugin-data state plugin-id)]
        {:file-id file-id
         :plugin-name (:name plugin-data)
         :plugin-url (:url plugin-data)}))))

;; Formats an event from the plugin system
(defn event
  [plugin-id name & {:as props}]
  (-> props
      (assoc ::ev/name name)
      (assoc ::ev/origin (get-origin plugin-id))
      (assoc ::ev/context (get-context @st/state plugin-id))
      (ev/event)))

(defn add-event
  [event plugin-id]
  (with-meta
    event
    {::ev/origin (get-origin plugin-id)
     ::ev/context (get-context @st/state plugin-id)}))
