;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.mcp
  (:require
   [app.common.logging :as log]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.plugins :as dp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.register :refer [mcp-plugin-id]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(log/set-level! :info)

(def ^:private default-manifest
  {:code "plugin.js"
   :name "Penpot MCP Plugin"
   :version 2
   :plugin-id mcp-plugin-id
   :description "This plugin enables interaction with the Penpot MCP server"
   :allow-background true
   :permissions
   #{"library:read" "library:write"
     "comment:read" "comment:write"
     "content:write" "content:read"}})

(defn finalize-workspace?
  [event]
  (= (ptk/type event) :app.main.data.workspace/finalize-workspace))

(defn update-mcp-status
  [value]
  (ptk/reify ::update-mcp-status
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] assoc :mcp-enabled value))

    ptk/WatchEvent
    (watch [_ _ _]
      (case value
        true  (rx/of (ptk/data-event ::connect))
        false (rx/of (ptk/data-event ::disconnect))
        nil))))

(defn update-mcp-connection
  [value]
  (ptk/reify ::update-mcp-plugin-connection
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :mcp] assoc :connected value))))

(defn init-mcp!
  [stream]
  (->> (rp/cmd! :get-current-mcp-token)
       (rx/subs!
        (fn [{:keys [token]}]
          (when token
            (dp/start-plugin!
             (assoc default-manifest
                    :url (str (u/join cf/public-uri "plugins/mcp/manifest.json"))
                    :host (str (u/join cf/public-uri "plugins/mcp/")))

             ;; API extension for MCP server
             #js {:mcp
                  #js
                   {:getToken (constantly token)
                    :getServerUrl #(str cf/mcp-ws-uri)
                    :setMcpStatus
                    (fn [status]
                      (let [mcp-connected? (case status
                                             "connected"    true
                                             "disconnected" false
                                             nil)]
                        (st/emit! (update-mcp-connection mcp-connected?))
                        (log/info :hint "MCP STATUS" :status status)))

                    :on
                    (fn [event cb]
                      (when-let [event
                                 (case event
                                   "disconnect" ::disconnect
                                   "connect" ::connect
                                   nil)]

                        (let [stopper (rx/filter finalize-workspace? stream)]
                          (->> stream
                               (rx/filter (ptk/type? event))
                               (rx/take-until stopper)
                               (rx/subs! #(cb))))))}}))))))

(defn disconnect-mcp
  []
  (st/emit! (ptk/data-event ::disconnect)))

(defn connect-mcp
  []
  (st/emit! (ptk/data-event ::connect)))

(defn init-mcp-connection
  []
  (ptk/reify ::init-mcp-connection
    ptk/EffectEvent
    (effect [_ state stream]
      (when (and (contains? cf/flags :mcp)
                 (-> state :profile :props :mcp-enabled))
        (init-mcp! stream)))))
