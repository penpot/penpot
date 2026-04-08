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
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def retry-interval 10000)

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

(defonce interval-sub (atom nil))

(defn finalize-workspace?
  [event]
  (= (ptk/type event) :app.main.data.workspace/finalize-workspace))

(defn set-mcp-active
  [value]
  (ptk/reify ::set-mcp-active
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:mcp :active] value))))

(defn start-reconnect-watcher!
  []
  (st/emit! (set-mcp-active true))
  (when (nil? @interval-sub)
    (reset!
     interval-sub
     (ts/interval
      retry-interval
      (fn []
        ;; Try to reconnect if active and not connected
        (when-not (contains? #{"connecting" "connected"}
                             (-> @st/state :mcp :connection-status))
          (.log js/console "Reconnecting to MCP...")
          (st/emit! (ptk/data-event ::connect))))))))

(defn stop-reconnect-watcher!
  []
  (st/emit! (set-mcp-active false))
  (when @interval-sub
    (rx/dispose! @interval-sub)
    (reset! interval-sub nil)))

(declare manage-mcp-notification)

(defn handle-pong
  [{:keys [id data]}]
  (ptk/reify ::handle-pong
    ptk/UpdateEvent
    (update [_ state]
      (let [mcp-state (get state :mcp)]
        (cond
          (= "connected" (:connection-status data))
          (update state :mcp assoc :connected-tab id)

          (and (= "disconnected" (:connection-status data))
               (= id (:connection-status mcp-state)))
          (update state :mcp dissoc :connected-tab)

          :else
          state)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (manage-mcp-notification)))))

;; This event will arrive when a new workspace is open in another tab
(defn handle-ping
  []
  (ptk/reify ::handle-ping
    ptk/WatchEvent
    (watch [_ state _]
      (let [conn-status (get-in state [:mcp :connection-status])]
        (rx/of (mbc/event :mcp/pong {:connection-status conn-status}))))))

(defn notify-other-tabs-disconnect
  []
  (ptk/reify ::notify-other-tabs-disconnect
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (mbc/event :mcp/pong {:connection-status "disconnected"})))))

;; This event will arrive when the mcp is enabled in the dashboard
(defn update-mcp-status
  [value]
  (ptk/reify ::update-mcp-status
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:profile :props] assoc :mcp-enabled value))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/merge
       (rx/of (manage-mcp-notification))
       (case value
         true  (rx/of (ptk/data-event ::connect))
         false (rx/of (ptk/data-event ::disconnect))
         nil)))))

(defn update-mcp-connection-status
  [value]
  (ptk/reify ::update-mcp-plugin-connection
    ptk/UpdateEvent
    (update [_ state]
      (update state :mcp assoc :connection-status value))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (manage-mcp-notification)
             (mbc/event :mcp/pong {:connection-status value})))))

(defn connect-mcp
  []
  (ptk/reify ::connect-mcp
    ptk/UpdateEvent
    (update [_ state]
      (update state :mcp assoc :connected-tab (:session-id state)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (mbc/event :mcp/force-disconect {})
             (ptk/data-event ::connect)))))

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

(defn- manage-mcp-notification
  []
  (ptk/reify ::manage-mcp-notification
    ptk/WatchEvent
    (watch [_ state _]
      (let [mcp-state          (get state :mcp)

            mcp-enabled?       (-> state :profile :props :mcp-enabled)

            current-tab-id     (get state :session-id)
            connected-tab-id   (get mcp-state :connected-tab)]

        (if mcp-enabled?
          (if (= connected-tab-id current-tab-id)
            (rx/of (ntf/hide))
            (rx/of (ntf/dialog
                    {:content (tr "notifications.mcp.active-in-another-tab")
                     :cancel {:label (tr "labels.dismiss")
                              :callback #(st/emit! (ntf/hide)
                                                   (ev/event {::ev/name "confirm-mcp-tab-switch"
                                                              ::ev/origin "workspace-notification"}))}
                     :accept {:label (tr "labels.switch")
                              :callback #(st/emit! (connect-mcp)
                                                   (ev/event {::ev/name "dismiss-mcp-tab-switch"
                                                              ::ev/origin "workspace-notification"}))}})))
          (rx/of (ntf/hide)))))))

(defn init-mcp
  [stream]
  (->> (rp/cmd! :get-current-mcp-token)
       (rx/tap
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
                      (when (= status "connected")
                        (start-reconnect-watcher!))
                      (st/emit! (update-mcp-connection-status status))
                      (log/info :hint "MCP STATUS" :status status))

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
                               (rx/subs! #(cb))))))}}))))
       (rx/ignore)))

(defn init
  []
  (ptk/reify ::init
    ptk/UpdateEvent
    (update [_ state]
      (update state :mcp assoc :connected-tab (:session-id state) :active true))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper-s   (rx/merge
                        (rx/filter (ptk/type? :app.main.data.workspace/finalize-workspace) stream)
                        (rx/filter (ptk/type? ::init) stream))
            session-id (get state :session-id)
            enabled?   (-> state :profile :props :mcp-enabled)]

        (->> (rx/merge
              (if enabled?
                (rx/merge
                 (init-mcp stream)

                 (rx/of (mbc/event :mcp/ping {}))

                 (->> mbc/stream
                      (rx/filter (mbc/type? :mcp/ping))
                      (rx/filter (fn [{:keys [id]}]
                                   (not= session-id id)))
                      (rx/map handle-ping))

                 (->> mbc/stream
                      (rx/filter (mbc/type? :mcp/pong))
                      (rx/filter (fn [{:keys [id]}]
                                   (not= session-id id)))
                      (rx/map handle-pong))

                 (->> mbc/stream
                      (rx/filter (mbc/type? :mcp/force-disconect))
                      (rx/filter (fn [{:keys [id]}]
                                   (not= session-id id)))
                      (rx/map deref)
                      (rx/map (fn [] (user-disconnect-mcp)))))
                (rx/empty))

              (->> mbc/stream
                   (rx/filter (mbc/type? :mcp/enable))
                   (rx/mapcat (fn [_]
                                ;; NOTE: we don't need an explicit
                                ;; connect because the plugin has
                                ;; auto-connect
                                (rx/of (update-mcp-status true)
                                       (init)))))

              (->> mbc/stream
                   (rx/filter (mbc/type? :mcp/disable))
                   (rx/mapcat (fn [_]
                                (rx/of (update-mcp-status false)
                                       (init)
                                       (user-disconnect-mcp))))))

             (rx/take-until stoper-s))))))
