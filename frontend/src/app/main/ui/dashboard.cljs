;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.dashboard.shortcuts :as sc]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as notif]
   [app.main.data.plugins :as dp]
   [app.main.data.project :as dpj]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.files :refer [files-section*]]
   [app.main.ui.dashboard.fonts :refer [fonts-page* font-providers-page*]]
   [app.main.ui.dashboard.import]
   [app.main.ui.dashboard.libraries :refer [libraries-page*]]
   [app.main.ui.dashboard.projects :refer [projects-section*]]
   [app.main.ui.dashboard.search :refer [search-page*]]
   [app.main.ui.dashboard.sidebar :refer [sidebar*]]
   [app.main.ui.dashboard.team :refer [team-settings-page* team-members-page* team-invitations-page* webhooks-page*]]
   [app.main.ui.dashboard.templates :refer [templates-section*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.modal :refer [modal-container*]]
   [app.main.ui.workspace.plugins]
   [app.plugins.register :as preg]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.storage :as storage]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc dashboard-content*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [team projects project section search-term profile default-project]}]
  (let [container       (mf/use-ref)
        content-width   (mf/use-state 0)

        project-id      (:id project)
        team-id         (:id team)

        permissions     (:permissions team)

        default-project-id
        (get default-project :id)

        on-resize
        (mf/use-fn
         (fn [_]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (reset! content-width width))))

        clear-selected-fn
        (mf/use-fn
         #(st/emit! (dd/clear-selected-files)))

        show-templates?
        (and (contains? cf/flags :dashboard-templates-section)
             (:can-edit permissions))]

    (mf/with-effect []
      (let [key1 (events/listen js/window "resize" on-resize)]
        #(events/unlistenByKey key1)))

    (mf/use-effect on-resize)

    [:div {:class (stl/css :dashboard-content)
           :on-click clear-selected-fn
           :ref container}
     (case section
       :dashboard-recent
       (when (seq projects)
         [:*
          [:> projects-section*
           {:team team
            :projects projects
            :profile profile}]

          (when ^boolean show-templates?
            [:> templates-section*
             {:profile profile
              :project-id project-id
              :team-id team-id
              :default-project-id default-project-id
              :content-width @content-width}])])

       :dashboard-fonts
       [:> fonts-page* {:team team}]

       :dashboard-font-providers
       [:> font-providers-page* {:team team}]

       :dashboard-files
       (when project
         [:*
          [:> files-section* {:team team
                              :project project}]
          (when ^boolean show-templates?
            [:> templates-section*
             {:profile profile
              :team-id team-id
              :project-id project-id
              :default-project-id default-project-id
              :content-width @content-width}])])

       :dashboard-search
       [:> search-page* {:team team
                         :search-term search-term}]

       :dashboard-libraries
       [:> libraries-page* {:team team
                            :default-project default-project}]

       :dashboard-members
       [:> team-members-page* {:team team :profile profile}]

       :dashboard-invitations
       [:> team-invitations-page* {:team team}]

       :dashboard-webhooks
       [:> webhooks-page* {:team team}]

       :dashboard-settings
       [:> team-settings-page* {:team team :profile profile}]

       nil)]))

(def ref:dashboard-initialized
  (l/derived :team-initialized st/state))

(defn use-plugin-register
  [plugin-url team-id project-id]

  (let [navegate-file!
        (fn [plugin {:keys [project-id id data]}]
          (st/emit!
           (dp/delay-open-plugin plugin)
           (rt/nav :workspace
                   {:page-id (dm/get-in data [:pages 0])
                    :project-id project-id
                    :file-id id})))

        create-file!
        (fn [plugin]
          (st/emit!
           (modal/hide)
           (let [data
                 (with-meta
                   {:project-id project-id
                    :name (dm/str (tr "dashboard.plugins.try-plugin") (:name plugin))}
                   {:on-success (partial navegate-file! plugin)})]
             (-> (dd/create-file data)
                 (with-meta {::ev/origin "plugin-try-out"})))))

        open-try-out-dialog
        (fn [plugin]
          (modal/show
           :plugin-try-out
           {:plugin plugin
            :on-accept #(create-file! plugin)
            :on-close #(modal/hide!)}))

        open-permissions-dialog
        (fn [plugin]
          (modal/show!
           :plugin-permissions
           {:plugin plugin
            :on-accept
            #(do (preg/install-plugin! plugin)
                 (st/emit! (modal/hide)
                           (rt/nav :dashboard-recent {:team-id team-id})
                           (open-try-out-dialog plugin)))
            :on-close
            #(st/emit! (modal/hide)
                       (rt/nav :dashboard-recent {:team-id team-id}))}))]

    (mf/with-layout-effect
      [plugin-url team-id project-id]
      (when plugin-url
        (->> (dp/fetch-manifest plugin-url)
             (rx/subs!
              (fn [plugin]
                (if plugin
                  (do
                    (st/emit! (ptk/event ::ev/event {::ev/name "install-plugin" :name (:name plugin) :url plugin-url}))
                    (open-permissions-dialog plugin))
                  (st/emit! (notif/error (tr "dashboard.plugins.parse-error")))))
              (fn [_]
                (st/emit! (notif/error (tr "dashboard.plugins.bad-url"))))))
        (binding [storage/*sync* true]
          (swap! storage/session dissoc :plugin-url))))))

(defn use-templates-import
  [can-edit? template-url default-project-id]
  (mf/with-layout-effect
    [can-edit? template-url default-project-id]
    (when (and (some? template-url) (some? default-project-id))
      (if can-edit?
        (let [valid-url?    (and (str/ends-with? template-url ".penpot")
                                 (str/starts-with? template-url cf/templates-uri))
              template-name (when valid-url? (subs template-url (count cf/templates-uri)))
              on-import     #(st/emit! (dpj/fetch-files default-project-id)
                                       (dd/fetch-recent-files)
                                       (dd/fetch-projects)
                                       (dd/clear-selected-files)
                                       (ptk/event ::ev/event {::ev/name "install-template-from-link-finished"
                                                              :name template-name
                                                              :url template-url}))]
          (if valid-url?
            (do
              (st/emit! (ptk/event ::ev/event {::ev/name "install-template-from-link" :name template-name :url template-url}))
              (->> (http/send! {:method :get
                                :uri template-url
                                :response-type :blob
                                :omit-default-headers true})
                   (rx/subs!
                    (fn [result]
                      (if (or (< (:status result) 200) (>= (:status result) 300))
                        (st/emit! (notif/error (tr "dashboard.import.error")))
                        (st/emit! (modal/show
                                   {:type :import
                                    :project-id default-project-id
                                    :entries [{:name template-name :uri (wapi/create-uri (:body result))}]
                                    :on-finish-import on-import})))))))
            (st/emit! (notif/error (tr "dashboard.import.bad-url")))))
        (st/emit! (notif/error (tr "dashboard.import.no-perms"))))

      (binding [storage/*sync* true]
        (swap! storage/session dissoc :template-url)))))

(mf/defc dashboard*
  {::mf/props :obj}
  [{:keys [profile project-id team-id search-term plugin-url template-url section]}]
  (let [team            (mf/deref refs/team)
        projects        (mf/deref refs/projects)

        project         (get projects project-id)
        projects        (mf/with-memo [projects team-id]
                          (->> (vals projects)
                               (filterv #(= team-id (:team-id %)))))

        can-edit?       (dm/get-in team [:permissions :can-edit])
        template-url    (or template-url (:template-url storage/session))
        plugin-url      (or plugin-url (:plugin-url storage/session))

        default-project
        (mf/with-memo [projects]
          (->> projects
               (filter :is-default)
               (first)))]

    (hooks/use-shortcuts ::dashboard sc/shortcuts)

    (mf/with-effect []
      (st/emit! (dd/initialize))
      (fn []
        (st/emit! (dd/finalize))))

    (mf/with-effect []
      (let [key (events/listen goog/global "keydown"
                               (fn [event]
                                 (when (kbd/enter? event)
                                   (dom/stop-propagation event)
                                   (st/emit! (dd/open-selected-file)))))]
        (fn []
          (events/unlistenByKey key))))

    (use-plugin-register plugin-url team-id (:id default-project))
    (use-templates-import can-edit? template-url (:id default-project))

    [:& (mf/provider ctx/current-project-id) {:value project-id}
     [:> modal-container*]
     ;; NOTE: dashboard events and other related functions assumes
     ;; that the team is a implicit context variable that is
     ;; available using react context or accessing
     ;; the :current-team-id on the state. We set the key to the
     ;; team-id because we want to completely refresh all the
     ;; components on team change. Many components assumes that the
     ;; team is already set so don't put the team into mf/deps.
     [:main {:class (stl/css :dashboard)
             :key (dm/str (:id team))}
      [:> sidebar*
       {:team team
        :projects projects
        :project project
        :default-project default-project
        :profile profile
        :section section
        :search-term search-term}]
      [:> dashboard-content*
       {:projects projects
        :profile profile
        :project project
        :default-project default-project
        :section section
        :search-term search-term
        :team team}]]]))
