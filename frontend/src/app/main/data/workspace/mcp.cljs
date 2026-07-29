;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.mcp
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.broadcast :as mbc]
   [app.main.data.plugins :as dp]
   [app.main.data.profile :as du]
   [app.main.store :as st]
   [app.plugins.register :as preg]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def reconnect-fallback-interval 60000)

(def reconnect-fallback-statuses
  #{"disconnected" "error"})

(log/set-level! :info)

(def ^:private
  default-manifest
  {:code "plugin.js"
   :name "Penpot MCP Plugin"
   :version 2
   :plugin-id preg/mcp-plugin-id
   :description "This plugin enables interaction with the Penpot MCP server"
   :allow-background true
   :permissions
   #{"library:read" "library:write"
     "comment:read" "comment:write"
     "content:write" "content:read"}})

(defonce interval-sub
  (atom nil))

(defn connect-mcp
  []
  (ptk/reify ::connect-mcp
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (mbc/event :mcp/force-disconnect {})
             (ptk/data-event ::connect)))))

(defn- start-reconnect-watcher
  []
  (when (nil? @interval-sub)
    (reset!
     interval-sub
     (ts/interval
      reconnect-fallback-interval
      (fn []
        ;; Slow app-level fallback. The plugin owns normal WebSocket
        ;; reconnects; this only restarts it if the app remains in a
        ;; failed connection state.
        (when (contains? reconnect-fallback-statuses
                         (-> @st/state :mcp :connection-status))
          (.log js/console "Reconnecting to MCP...")
          (st/emit! (ptk/data-event ::connect))))))))

(defn stop-reconnect-watcher!
  []
  (when @interval-sub
    (rx/dispose! @interval-sub)
    (reset! interval-sub nil)))

;; This event will arrive when the mcp is enabled in the dashboard
(defn update-mcp-status
  [value]
  (ptk/reify ::update-mcp-status
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :mcp assoc :enabled value)
          (update-in [:profile :props] assoc :mcp-enabled value)))

    ptk/WatchEvent
    (watch [_ _ _]
      (case value
        true  (rx/of (connect-mcp))
        false (rx/of (ptk/data-event ::disconnect))
        nil))))

(defn update-mcp-connection-status
  [value]
  (ptk/reify ::update-mcp-plugin-connection
    ptk/UpdateEvent
    (update [_ state]
      (update state :mcp assoc :connection-status value))

    ptk/WatchEvent
    (watch [_ _ _]
      ;; Only one MCP plugin instance may be active across browser tabs.
      ;; When this tab becomes connected, tell every other tab to
      ;; disconnect (which also stops their reconnect watcher). Otherwise
      ;; several tabs stay connected at once and the MCP server reports
      ;; "multiple instances connected" and the agent fails.
      (when (= "connected" value)
        (rx/of (mbc/event :mcp/force-disconnect {}))))))

;; This event will arrive when the user selects disconnect on the menu
;; or there is a broadcast message for disconnection
(defn user-disconnect-mcp
  []
  (ptk/reify ::user-disconnect-mcp
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (ptk/data-event ::disconnect)
             (update-mcp-connection-status "disconnected")))

    ptk/EffectEvent
    (effect [_ _ _]
      (stop-reconnect-watcher!))))

(defn- init-mcp-plugin
  "Waits the plugin runtime and initializes the bundled MCP plugin."
  [{:keys [token]}]
  (ptk/reify ::init-mcp-plugin
    ptk/EffectEvent
    (effect [_ _ stream]
      (let [manifest  (-> default-manifest
                          (assoc :url (str (u/join cf/public-uri "plugins/mcp/manifest.json")))
                          (assoc :host (str (u/join cf/public-uri "plugins/mcp/"))))

            stopper-s (rx/merge
                       (rx/filter (ptk/type? :app.main.data.workspace/finalize-workspace) stream)
                       (rx/filter (ptk/type? ::stop-mcp-plugin) stream))

            extension #js {:getToken (constantly token)
                           :getServerUrl #(str cf/mcp-ws-uri)
                           :setMcpStatus
                           (fn [status]
                             (when (= status "connected")
                               (start-reconnect-watcher))
                             (st/emit! (update-mcp-connection-status status))
                             (log/info :hint "MCP STATUS" :status status))

                           :on
                           (fn [event cb]
                             (when-let [event
                                        (case event
                                          "disconnect" ::disconnect
                                          "connect" ::connect
                                          nil)]

                               (->> stream
                                    (rx/filter (ptk/type? event))
                                    (rx/take-until stopper-s)
                                    (rx/subs! (fn [_] (cb))))))}]

        (dp/start-plugin! manifest #js {:mcp extension})))))

(defn- stop-mcp-plugin
  []
  (ptk/reify ::stop-mcp-plugin
    ptk/EffectEvent
    (effect [_ _ _]
      (dp/close-plugin! default-manifest))))

(defn- init-mcp-state
  [access-tokens]
  (let [token  (d/seek #(= "mcp" (:type %)) access-tokens)
        valid? (and token
                    (as-> (get token :expires-at) expires-at
                      (or (nil? expires-at)
                          (> expires-at (ct/now)))))]

    (ptk/reify ::init-mcp-state
      IDeref
      (-deref [_]
        (when valid? token))

      ptk/UpdateEvent
      (update [_ state]
        (if token
          (update state :mcp (fn [state]
                               (-> state
                                   (assoc :token (:token token))
                                   (assoc :token-id (:id token))
                                   (assoc :token-valid valid?))))
          state)))))

(defn init
  "Initialize MCP runtime. This event expects plugin runtime initialized."
  []
  (ptk/reify ::init
    ptk/UpdateEvent
    (update [_ state]
      (let [profile      (get state :profile)
            mcp-enabled? (-> profile :props :mcp-enabled boolean)]
        (update state :mcp assoc :enabled mcp-enabled?)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper-s  (rx/merge
                        (rx/filter (ptk/type? :app.main.data.workspace/finalize-workspace) stream)
                        (rx/filter (ptk/type? ::init) stream))

            session-id (get state :session-id)
            mcp-state  (get state :mcp)]

        (->> (rx/merge
              (rx/of (du/fetch-access-tokens))

              ;; Wait until access tokens are initialized and are
              ;; setup on the state
              (->> stream
                   (rx/filter (ptk/type? ::du/access-tokens-fetched))
                   (rx/map deref)
                   (rx/take 1)
                   (rx/map init-mcp-state))

              (if (:enabled mcp-state)
                (->> stream
                     (rx/filter (ptk/type? ::init-mcp-state))
                     (rx/take 1)
                     (rx/map deref)
                     (rx/filter some?)
                     (rx/map init-mcp-plugin))
                (rx/empty))

              (->> mbc/stream
                   (rx/filter (mbc/type? :mcp/force-disconnect))
                   (rx/filter (fn [{:keys [id]}]
                                (not= session-id id)))
                   (rx/map deref)
                   (rx/map (fn [] (user-disconnect-mcp))))

              (->> mbc/stream
                   (rx/filter (mbc/type? :mcp/enable))
                   (rx/mapcat (fn [_]
                                ;; Re-init so the force-disconnect
                                ;; listener is set up now that MCP
                                ;; is enabled.
                                (rx/of (update-mcp-status true)
                                       (init)))))

              (->> mbc/stream
                   (rx/filter (mbc/type? :mcp/disable))
                   (rx/mapcat (fn [_]
                                (rx/of (update-mcp-status false)
                                       (user-disconnect-mcp)
                                       (stop-mcp-plugin))))))

             (rx/take-until stopper-s))))))
