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
   [app.main.broadcast :as mbc]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.plugins :as dp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.register :refer [mcp-plugin-id]]
   [app.util.i18n :refer [tr]]
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

(defn disconnect-mcp
  []
  (st/emit! (ptk/data-event ::disconnect)))

(defn connect-mcp
  []
  (ptk/reify ::connect-mcp
    ptk/WatchEvent
    (watch [_ _ stream]
      (mbc/emit! :mcp-enabled-change-connection false)
      (->> stream
           (rx/filter (ptk/type? ::disconnect))
           (rx/take 1)
           (rx/map #(ptk/data-event ::connect))))))

(defn manage-mcp-notification
  []
  (ptk/reify ::manage-mcp-notification
    ptk/WatchEvent
    (watch [_ state _]
      (let [mcp-connected? (true? (-> state :workspace-local :mcp :connection))
            mcp-enabled?   (true? (-> state :profile :props :mcp-enabled))
            num-sessions   (-> state :workspace-presence vals count)
            multi-session? (> num-sessions 1)]
        (if (and mcp-enabled? multi-session?)
          (if mcp-connected?
            (rx/of (ntf/hide))
            (rx/of (ntf/dialog :content (tr "notifications.mcp.active-in-another-tab")
                               :cancel {:label (tr "labels.dismiss")
                                        :callback #(st/emit! (ntf/hide)
                                                             (ptk/event ::ev/event {::ev/name "confirm-mcp-tab-switch"
                                                                                    ::ev/origin "workspace-notification"}))}
                               :accept {:label (tr "labels.switch")
                                        :callback #(st/emit! (connect-mcp)
                                                             (ptk/event ::ev/event {::ev/name "dismiss-mcp-tab-switch"
                                                                                    ::ev/origin "workspace-notification"}))})))
          (rx/of (ntf/hide)))))))

(defn update-mcp-status
  [value]
  (ptk/reify ::update-mcp-status
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] assoc :mcp-enabled value))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/merge
       (rx/of (manage-mcp-notification)))
      (case value
        true  (rx/of (ptk/data-event ::connect))
        false (rx/of (ptk/data-event ::disconnect))
        nil))))

(defn update-mcp-connection
  [value]
  (ptk/reify ::update-mcp-plugin-connection
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :mcp] assoc :connection value))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (manage-mcp-notification)))))

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
                      (let [mcp-connection (case status
                                             "connected"    true
                                             "disconnected" false
                                             "error"        nil
                                             "")]
                        (st/emit! (update-mcp-connection mcp-connection))
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

(defn init-mcp-connection
  []
  (ptk/reify ::init-mcp-connection
    ptk/EffectEvent
    (effect [_ state stream]
      (when (and (contains? cf/flags :mcp)
                 (-> state :profile :props :mcp-enabled))
        (init-mcp! stream)))))
