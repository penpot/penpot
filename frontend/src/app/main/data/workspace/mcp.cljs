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

(defn init-mcp!
  []
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
                      ;; TODO: Visual feedback
                      (log/info :hint "MCP STATUS" :status status))}}))))))

(defn init-mcp-connexion
  []
  (ptk/reify ::init-mcp-connexion
    ptk/EffectEvent
    (effect [_ state _]
      (when (and (contains? cf/flags :mcp)
                 (-> state :profile :props :mcp-status))
        (init-mcp!)))))
