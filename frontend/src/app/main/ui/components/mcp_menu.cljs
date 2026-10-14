;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.components.mcp-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.mcp :as mcp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu* dropdown-menu-item*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(defn- cancel-timer!
  [timer-ref*]
  (when-let [timer (mf/ref-val timer-ref*)]
    (ts/dispose! timer)
    (mf/set-ref-val! timer-ref* nil)))

(mf/defc mcp-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [is-mcp-connected is-connection-requested session-id dashboard]}]
  (let [copied-text (tr "workspace.toolbar.mcp-session-copied")
        copy-error (tr "errors.clipboard-api-unavailable")
        menu-open*   (mf/use-state false)
        menu-open?   (deref menu-open*)

        open-timer*  (mf/use-ref nil)
        close-timer* (mf/use-ref nil)

        on-toggle-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (cancel-timer! open-timer*)
           (cancel-timer! close-timer*)
           (swap! menu-open* not)))

        on-close-menu
        (mf/use-fn
         (fn []
           (cancel-timer! open-timer*)
           (cancel-timer! close-timer*)
           (reset! menu-open* false)))

        on-display-menu
        (mf/use-fn
         (fn []
           (cancel-timer! close-timer*)
           (cancel-timer! open-timer*)
           (mf/set-ref-val!
            open-timer*
            (ts/schedule 350
                         #(do
                            (reset! menu-open* true)
                            (mf/set-ref-val! open-timer* nil))))))

        on-hide-menu
        (mf/use-fn
         (fn []
           (cancel-timer! open-timer*)
           (cancel-timer! close-timer*)
           (mf/set-ref-val!
            close-timer*
            (ts/schedule 350
                         #(do
                            (reset! menu-open* false)
                            (mf/set-ref-val! close-timer* nil))))))

        on-connect
        (mf/use-fn
         (mf/deps dashboard)
         #(st/emit! (mcp/connect-mcp)
                    (ev/event {::ev/name "connect-mcp-plugin"
                               ::ev/origin (if dashboard "dashboard:header" "workspace:toolbar")})))

        on-disconnect
        (mf/use-fn
         #(st/emit! (mcp/user-disconnect-mcp)))

        on-copy-session
        (mf/use-fn
         (mf/deps session-id copied-text copy-error)
         (fn []
           (-> (clipboard/to-clipboard session-id)
               (.then #(st/emit! (ntf/info copied-text)))
               (.catch #(st/emit! (ntf/error copy-error))))))]

    (mf/with-effect []
      (fn []
        (cancel-timer! open-timer*)
        (cancel-timer! close-timer*)))

    [:div {:class (stl/css-case :mcp-tool true :dashboard dashboard)
           :on-pointer-enter on-display-menu
           :on-pointer-leave on-hide-menu}
     [:> button* {:variant (if dashboard "secondary" "ghost")
                  :on-click on-toggle-menu
                  :aria-haspopup true
                  :aria-expanded menu-open?
                  :aria-pressed menu-open?
                  :data-tool "mcp"
                  :data-testid "mcp-btn"}
      [:div {:class (stl/css-case :toolbar-mcp-button true
                                  :selected menu-open?)}
       [:span {:class (stl/css-case :toolbar-mcp-button-dot true
                                    :connected is-mcp-connected)}]
       [:span {:class (stl/css-case :toolbar-mcp-button-label true
                                    :connected is-mcp-connected)}
        (tr "workspace.toolbar.mcp")]]]

     [:div {:class (stl/css :toolbar-mcp-menu)}
      [:> dropdown-menu* {:show menu-open?
                          :on-close on-close-menu
                          :class (stl/css :toolbar-mcp-dropdown)}
       (when (or is-mcp-connected session-id)
         [:li {:class (stl/css :toolbar-mcp-dropdown-info)
               :role "presentation"}
          (when is-mcp-connected
            [:span (tr "workspace.toolbar.mcp-connected")])
          (when session-id
            [:span (tr "workspace.toolbar.mcp-session-id" session-id)])])
       (when session-id
         [:> dropdown-menu-item* {:class (stl/css :toolbar-mcp-dropdown-item)
                                  :on-click on-copy-session}
          (tr "workspace.toolbar.mcp-copy-session-id")])
       [:> dropdown-menu-item* {:class (stl/css :toolbar-mcp-dropdown-item)
                                :on-click (if is-connection-requested on-disconnect on-connect)}
        (if is-connection-requested
          (tr "workspace.header.menu.mcp.plugin.status.disconnect")
          (tr "workspace.header.menu.mcp.plugin.status.connect"))]]]]))

(mf/defc dashboard-mcp-menu*
  []
  (let [mcp-state (mf/deref refs/mcp)]
    (when (and (contains? cf/flags :mcp)
               (:enabled mcp-state)
               (:token-valid mcp-state))
      [:> mcp-menu* {:dashboard true
                     :is-mcp-connected (= "connected" (:connection-status mcp-state))
                     :is-connection-requested (:connection-requested mcp-state)
                     :session-id (:session-id mcp-state)}])))
