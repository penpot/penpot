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
   [app.config :as cfg]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.plugins :as dp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.icons :as i]
   [app.plugins.register :as preg]
   [app.util.avatars :as avatars]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(defn icon-url
  "Creates an sanitizes de icon URL to display"
  [host icon]
  (dm/str host
          (if (and (not (str/ends-with? host "/"))
                   (not (str/starts-with? icon "/")))
            "/" "")
          icon))

(mf/defc plugin-entry
  [{:keys [index manifest user-can-edit on-open-plugin on-remove-plugin]}]

  (let [{:keys [plugin-id host icon name description permissions]} manifest
        plugins-permissions-peek (deref refs/plugins-permissions-peek)
        permissions              (or (get plugins-permissions-peek plugin-id)
                                     permissions)
        is-edition-plugin?       (or (contains? permissions "content:write")
                                     (contains? permissions "library:write"))
        can-open?                (or user-can-edit
                                     (not is-edition-plugin?))

        handle-open-click
        (mf/use-fn
         (mf/deps index manifest on-open-plugin can-open?)
         (fn []
           (when (and can-open? on-open-plugin)
             (on-open-plugin manifest))))

        handle-delete-click
        (mf/use-fn
         (mf/deps index on-remove-plugin)
         (fn []
           (when on-remove-plugin
             (on-remove-plugin index))))]
    [:div {:class (stl/css :plugins-list-element)}
     [:div {:class (stl/css :plugin-icon)}
      [:img {:src (if (some? icon)
                    (icon-url host icon)
                    (avatars/generate {:name name}))}]]
     [:div {:class (stl/css :plugin-description)}
      [:div {:class (stl/css :plugin-title)} name]
      [:div {:class (stl/css :plugin-summary)} (d/nilv description "")]]


     [:> button* {:class (stl/css :open-button)
                  :variant "secondary"
                  :on-click handle-open-click
                  :title (when-not can-open? (tr "workspace.plugins.error.need-editor"))
                  :disabled (not can-open?)} (tr "workspace.plugins.button-open")]

     [:> icon-button* {:variant "ghost"
                       :aria-label (tr "workspace.plugins.remove-plugin")
                       :on-click handle-delete-click
                       :icon "delete"}]]))

(mf/defc plugin-management-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-management}
  []

  (let [plugins-state* (mf/use-state #(preg/plugins-list))
        plugins-state @plugins-state*

        plugin-url* (mf/use-state "")
        plugin-url  @plugin-url*

        fetching-manifest? (mf/use-state false)

        input-status* (mf/use-state nil) ;; :error-url :error-manifest :success
        input-status  @input-status*

        error-url? (= :error-url input-status)
        error-manifest? (= :error-manifest input-status)
        error? (or error-url? error-manifest?)

        user-can-edit? (:can-edit (deref refs/permissions))

        handle-url-input
        (mf/use-fn
         (fn [value]
           (reset! input-status* nil)
           (reset! plugin-url* value)))

        handle-install-click
        (mf/use-fn
         (mf/deps plugins-state plugin-url)
         (fn []
           (reset! fetching-manifest? true)
           (->> (dp/fetch-manifest plugin-url)
                (rx/subs!
                 (fn [plugin]
                   (reset! fetching-manifest? false)
                   (if plugin
                     (do
                       (st/emit! (ptk/event ::ev/event {::ev/name "install-plugin" :name (:name plugin) :url plugin-url}))
                       (modal/show!
                        :plugin-permissions
                        {:plugin plugin
                         :on-accept
                         #(do
                            (preg/install-plugin! plugin)
                            (modal/show! :plugin-management {}))})
                       (reset! input-status* :success)
                       (reset! plugin-url* ""))
                     ;; Cannot get the manifest
                     (reset! input-status* :error-manifest)))
                 (fn [err]
                   (.error js/console err)
                   (reset! fetching-manifest? false)
                   (reset! input-status* :error-url))))))

        handle-open-plugin
        (mf/use-fn
         (fn [manifest]
           (st/emit! (ptk/event ::ev/event {::ev/name "start-plugin"
                                            ::ev/origin "workspace:plugins"
                                            :name (:name manifest)
                                            :host (:host manifest)}))
           (dp/open-plugin! manifest user-can-edit?)
           (modal/hide!)))

        handle-remove-plugin
        (mf/use-fn
         (mf/deps plugins-state)
         (fn [plugin-index]
           (let [plugins-list (preg/plugins-list)
                 plugin (nth plugins-list plugin-index)]
             (st/emit! (ptk/event ::ev/event {::ev/name "remove-plugin"
                                              :name (:name plugin)
                                              :host (:host plugin)}))
             (dp/close-plugin! plugin)
             (preg/remove-plugin! plugin)
             (reset! plugins-state* (preg/plugins-list)))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :plugin-management)}
      [:button {:class (stl/css :close-btn) :on-click modal/hide!} close-icon]
      [:div {:class (stl/css :modal-title)} (tr "workspace.plugins.title")]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :top-bar)}
        [:& search-bar {:on-change handle-url-input
                        :value plugin-url
                        :placeholder (tr "workspace.plugins.search-placeholder")
                        :class (stl/css-case :input-error error?)}]

        [:button {:class (stl/css :primary-button)
                  :disabled @fetching-manifest?
                  :on-click handle-install-click} (tr "workspace.plugins.install")]]

       (when error-url?
         [:div {:class (stl/css-case :info true :error error?)}
          (tr "workspace.plugins.error.url")])

       (when error-manifest?
         [:div {:class (stl/css-case :info true :error error?)}
          (tr "workspace.plugins.error.manifest")])

       (when-not (empty? plugins-state)
         [:> i18n/tr-html*
          {:class (stl/css :discover)
           :on-click #(st/emit! (ptk/event ::ev/event {::ev/name "open-plugins-list"}))
           :content (tr "workspace.plugins.discover" cfg/plugins-list-uri)}])

       [:hr]

       (if (empty? plugins-state)
         [:div {:class (stl/css :plugins-empty)}
          [:div {:class (stl/css :plugins-empty-logo)} i/puzzle]
          [:div {:class (stl/css :plugins-empty-text)} (tr "workspace.plugins.empty-plugins")]
          [:a {:class (stl/css :plugins-link)
               :href cfg/plugins-list-uri
               :target "_blank"
               :on-click #(st/emit! (ptk/event ::ev/event {::ev/name "open-plugins-list"}))}
           (tr "workspace.plugins.plugin-list-link") i/external-link]]

         [:*
          [:& title-bar {:collapsable false
                         :title (tr "workspace.plugins.installed-plugins")}]

          [:div {:class (stl/css :plugins-list)}
           (for [[idx manifest] (d/enumerate plugins-state)]
             [:& plugin-entry {:key (dm/str "plugin-" idx)
                               :index idx
                               :manifest manifest
                               :user-can-edit user-can-edit?
                               :on-open-plugin handle-open-plugin
                               :on-remove-plugin handle-remove-plugin}])]])]]]))

(mf/defc plugins-permission-list
  [{:keys [permissions]}]
  [:div {:class (stl/css :permissions-list)}
   (cond
     (contains? permissions "content:write")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-1
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.content-write")]]

     (contains? permissions "content:read")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-1
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.content-read")]])

   (cond
     (contains? permissions "user:read")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-2
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.user-read")]])

   (cond
     (contains? permissions "library:write")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-3
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.library-write")]]

     (contains? permissions "library:read")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-3
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.library-read")]])

   (cond
     (contains? permissions "comment:write")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-1
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.comment-write")]]

     (contains? permissions "comment:read")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-1
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.comment-read")]])

   (cond
     (contains? permissions "allow:downloads")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-1
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.allow-download")]])

   (cond
     (contains? permissions "allow:localstorage")
     [:div {:class (stl/css :permissions-list-entry)}
      i/oauth-1
      [:p {:class (stl/css :permissions-list-text)}
       (tr "workspace.plugins.permissions.allow-localstorage")]])])

(mf/defc plugins-permissions-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-permissions}
  [{:keys [plugin on-accept on-close]}]

  (let [{:keys [host permissions]} plugin
        permissions (set permissions)

        handle-accept-dialog
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (ptk/event ::ev/event {::ev/name "allow-plugin-permissions"
                                            :host host
                                            :permissions (->> permissions (str/join ", "))})
                     (modal/hide))
           (when on-accept (on-accept))))

        handle-close-dialog
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (ptk/event ::ev/event {::ev/name "reject-plugin-permissions"
                                            :host host
                                            :permissions (->> permissions (str/join ", "))})
                     (modal/hide))
           (when on-close (on-close))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :plugin-permissions)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)} (tr "workspace.plugins.permissions.title" (str/upper (:name plugin)))]

      [:div {:class (stl/css :modal-content)}
       [:& plugins-permission-list {:permissions permissions}]

       (when-not (contains? cfg/plugins-whitelist host)
         [:div {:class (stl/css :permissions-disclaimer)}
          (tr "workspace.plugins.permissions.disclaimer")])]

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


(mf/defc plugins-permissions-updated-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-permissions-update}
  [{:keys [plugin on-accept on-close]}]

  (let [{:keys [host permissions]} plugin
        permissions (set permissions)

        handle-accept-dialog
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (ptk/event ::ev/event {::ev/name "allow-plugin-permissions"
                                            :host host
                                            :permissions (->> permissions (str/join ", "))})
                     (modal/hide))
           (when on-accept (on-accept))))

        handle-close-dialog
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (ptk/event ::ev/event {::ev/name "reject-plugin-permissions"
                                            :host host
                                            :permissions (->> permissions (str/join ", "))})
                     (modal/hide))
           (when on-close (on-close))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :plugin-permissions)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)}
       (tr "workspace.plugins.permissions-update.title" (str/upper (:name plugin)))]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-paragraph)}
        (tr "workspace.plugins.permissions-update.warning")]
       [:& plugins-permission-list {:permissions permissions}]]

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


(mf/defc plugins-try-out-dialog
  {::mf/register modal/components
   ::mf/register-as :plugin-try-out}
  [{:keys [plugin on-accept on-close]}]

  (let [{:keys [icon host name]} plugin

        handle-accept-dialog
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (ptk/event ::ev/event {::ev/name "try-out-accept"})
                     (modal/hide))
           (when on-accept (on-accept))))

        handle-close-dialog
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (ptk/event ::ev/event {::ev/name "try-out-cancel"})
                     (modal/hide))
           (when on-close (on-close))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :plugin-try-out)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)}
       [:div {:class (stl/css :plugin-icon)}
        [:img {:src (if (some? icon)
                      (icon-url host icon)
                      (avatars/generate {:name name}))}]]
       (tr "workspace.plugins.try-out.title" (str/upper (:name plugin)))]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-message)}
        (tr "workspace.plugins.try-out.message")]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input
         {:class (stl/css :cancel-button :button-expand)
          :type "button"
          :value (tr "workspace.plugins.try-out.cancel")
          :on-click handle-close-dialog}]

        [:input
         {:class (stl/css :primary-button :button-expand)
          :type "button"
          :value (tr "workspace.plugins.try-out.try")
          :on-click handle-accept-dialog}]]]]]))
