;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.plugins
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.plugins :as plugins]
   [app.util.avatars :as avatars]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(mf/defc plugin-entry
  [{:keys [index manifest on-open-plugin on-remove-plugin]}]

  (let [{:keys [host icon name description]} manifest
        handle-open-click
        (mf/use-callback
         (mf/deps index manifest on-open-plugin)
         (fn []
           (when on-open-plugin
             (on-open-plugin manifest))))

        handle-delete-click
        (mf/use-callback
         (mf/deps index on-remove-plugin)
         (fn []
           (when on-remove-plugin
             (on-remove-plugin index))))]
    [:div {:class (stl/css :plugins-list-element)}
     [:div {:class (stl/css :plugin-icon)}
      [:img {:src (if (some? icon)
                    (dm/str host icon)
                    (avatars/generate {:name name}))}]]
     [:div {:class (stl/css :plugin-description)}
      [:div {:class (stl/css :plugin-title)} name]
      [:div {:class (stl/css :plugin-summary)} (d/nilv description "")]]
     [:button {:class (stl/css :open-button)
               :on-click handle-open-click} (tr "workspace.plugins.button-open")]
     [:button {:class (stl/css :trash-button)
               :on-click handle-delete-click} i/delete]]))


(defn open-plugin!
  [{:keys [plugin-id name description host code icon permissions]}]
  (try
    (.ɵloadPlugin
     js/window
     #js {:pluginId plugin-id
          :name name
          :description description
          :host host
          :code code
          :icon icon
          :permissions (apply array permissions)})
    (catch :default e
      (.error js/console "Error" e))))

(mf/defc plugin-management-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-management}
  []

  (let [plugins-state* (mf/use-state @plugins/pluginsdb)
        plugins-state @plugins-state*

        plugin-url* (mf/use-state "")
        plugin-url  @plugin-url*

        input-status* (mf/use-state nil) ;; :error-url :error-manifest :success
        input-status  @input-status*

        error? (contains? #{:error-url :error-manifest} input-status)

        handle-close-dialog
        (mf/use-callback
         (fn []
           (modal/hide!)))

        handle-url-input
        (mf/use-callback
         (fn [value]
           (reset! input-status* nil)
           (reset! plugin-url* value)))

        handle-install-click
        (mf/use-callback
         (mf/deps plugins-state plugin-url)
         (fn []
           (->> (http/send! {:method :get
                             :uri plugin-url
                             :omit-default-headers true
                             :response-type :json})
                (rx/map :body)
                (rx/subs!
                 (fn [body]
                   (let [plugin (plugins/parser-manifest plugin-url body)]
                     (modal/show!
                      :plugin-permissions
                      {:plugin plugin
                       :on-accept
                       #(do
                          (plugins/install-plugin! plugin)
                          (modal/show! :plugin-management {}))})
                     (reset! input-status* :success)
                     (reset! plugin-url* "")))
                 (fn [_]
                   (reset! input-status* :error-url))))))

        handle-open-plugin
        (mf/use-callback
         (fn [manifest]
           (open-plugin! manifest)
           (modal/hide!)))

        handle-remove-plugin
        (mf/use-callback
         (mf/deps plugins-state)
         (fn [plugin-index]
           (let [plugin (nth @plugins/pluginsdb plugin-index)]
             (plugins/remove-plugin! plugin)
             (reset! plugins-state* @plugins/pluginsdb))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :plugin-management)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)} (tr "workspace.plugins.title")]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :top-bar)}
        [:& search-bar {:on-change handle-url-input
                        :value plugin-url
                        :placeholder (tr "workspace.plugins.search-placeholder")
                        :class (stl/css-case :input-error error?)}]

        [:button {:class (stl/css :primary-button)
                  :on-click handle-install-click} (tr "workspace.plugins.install")]]

       (when error?
         [:div {:class (stl/css-case :info true :error error?)}
          (tr "workspace.plugins.error.url")])

       [:hr]

       (if (empty? plugins-state)
         [:div {:class (stl/css :plugins-empty)}
          [:div {:class (stl/css :plugins-empty-logo)} i/puzzle]
          [:div {:class (stl/css :plugins-empty-text)} (tr "workspace.plugins.empty-plugins")]
          [:a {:class (stl/css :plugins-link) :href cf/plugins-list-uri :target "_blank"}
           (tr "workspace.plugins.plugin-list-link") i/external-link]]

         [:*
          [:& title-bar {:collapsable false
                         :title (tr "workspace.plugins.installed-plugins")}]

          [:div {:class (stl/css :plugins-list)}
           (for [[idx manifest] (d/enumerate plugins-state)]
             [:& plugin-entry {:key (dm/str "plugin-" idx)
                               :index idx
                               :manifest manifest
                               :on-open-plugin handle-open-plugin
                               :on-remove-plugin handle-remove-plugin}])]])]]]))

(mf/defc plugins-permissions-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-permissions}
  [{:keys [plugin on-accept]}]

  (let [{:keys [permissions]} plugin
        permissions (set permissions)

        handle-accept-dialog
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))
           (on-accept)))

        handle-close-dialog
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :plugin-permissions)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)} (tr "workspace.plugins.permissions.title")]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :permissions-list)}
        (when (contains? permissions "content:read")
          [:div {:class (stl/css :permissions-list-entry)}
           i/oauth-1
           [:p {:class (stl/css :permissions-list-text)}
            (tr "workspace.plugins.permissions.content-read")]])

        (when (contains? permissions "content:write")
          [:div {:class (stl/css :permissions-list-entry)}
           i/oauth-1
           [:p {:class (stl/css :permissions-list-text)}
            (tr "workspace.plugins.permissions.content-write")]])

        (when (contains? permissions "user:read")
          [:div {:class (stl/css :permissions-list-entry)}
           i/oauth-2
           [:p {:class (stl/css :permissions-list-text)}
            (tr "workspace.plugins.permissions.user-read")]])

        (when (contains? permissions "library:read")
          [:div {:class (stl/css :permissions-list-entry)}
           i/oauth-3
           [:p {:class (stl/css :permissions-list-text)}
            (tr "workspace.plugins.permissions.library-read")]])

        (when (contains? permissions "library:write")
          [:div {:class (stl/css :permissions-list-entry)}
           i/oauth-3
           [:p {:class (stl/css :permissions-list-text)}
            (tr "workspace.plugins.permissions.library-write")]])]

       [:div {:class (stl/css :permissions-disclaimer)}
        (tr "workspace.plugins.permissions.disclaimer")]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input
         {:class (stl/css :cancel-button :button-expand)
          :type "button"
          :value (tr "ds.confirm-cancel")
          :on-click handle-close-dialog}]

        [:input
         {:class (stl/css :primary-button :button-expand)
          :type "button"
          :value (tr "ds.confirm-allow")
          :on-click handle-accept-dialog}]]]]]))
