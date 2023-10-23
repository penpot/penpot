;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.dashboard.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.export]
   [app.main.ui.dashboard.files :refer [files-section]]
   [app.main.ui.dashboard.fonts :refer [fonts-page font-providers-page]]
   [app.main.ui.dashboard.import]
   [app.main.ui.dashboard.libraries :refer [libraries-page]]
   [app.main.ui.dashboard.projects :refer [projects-section]]
   [app.main.ui.dashboard.search :refer [search-page]]
   [app.main.ui.dashboard.sidebar :refer [sidebar]]
   [app.main.ui.dashboard.team :refer [team-settings-page team-members-page team-invitations-page team-webhooks-page]]
   [app.main.ui.dashboard.templates :refer [templates-section]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(defn ^boolean uuid-str?
  [s]
  (and (string? s)
       (boolean (re-seq us/uuid-rx s))))

(defn- parse-params
  [route]
  (let [search-term (get-in route [:params :query :search-term])
        team-id     (get-in route [:params :path :team-id])
        project-id  (get-in route [:params :path :project-id])]
    (cond->
      {:search-term search-term}

      (uuid-str? team-id)
      (assoc :team-id (uuid team-id))

      (uuid-str? project-id)
      (assoc :project-id (uuid project-id)))))

(mf/defc dashboard-content
  [{:keys [team projects project section search-term profile] :as props}]
  (let [container          (mf/use-ref)
        content-width      (mf/use-state 0)
        project-id         (:id project)
        team-id            (:id team)

        default-project-id
        (mf/with-memo [projects]
          (->> (vals projects)
               (d/seek :is-default)
               (:id)))


        on-resize
        (mf/use-fn
         (fn [_]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (reset! content-width width))))

        clear-selected-fn
        (mf/use-fn
         #(st/emit! (dd/clear-selected-files)))]

    (mf/with-effect []
      (let [key1 (events/listen js/window "resize" on-resize)]
        #(events/unlistenByKey key1)))

    (mf/use-effect on-resize)

    [:div.dashboard-content {:on-click clear-selected-fn :ref container}
     (case section
       :dashboard-projects
       [:*
        [:& projects-section
         {:team team
          :projects projects
          :profile profile
          :default-project-id default-project-id}]

        (when (contains? cf/flags :dashboard-templates-section)
          [:& templates-section {:profile profile
                                 :project-id project-id
                                 :team-id team-id
                                 :default-project-id default-project-id
                                 :content-width @content-width}])]

       :dashboard-fonts
       [:& fonts-page {:team team}]

       :dashboard-font-providers
       [:& font-providers-page {:team team}]

       :dashboard-files
       (when project
         [:*
          [:& files-section {:team team :project project}]
          (when (contains? cf/flags :dashboard-templates-section)
            [:& templates-section {:profile profile
                                   :team-id team-id
                                   :project-id project-id
                                   :default-project-id default-project-id
                                   :content-width @content-width}])])

       :dashboard-search
       [:& search-page {:team team
                        :search-term search-term}]

       :dashboard-libraries
       [:& libraries-page {:team team}]

       :dashboard-team-members
       [:& team-members-page {:team team :profile profile}]

       :dashboard-team-invitations
       [:& team-invitations-page {:team team}]

       :dashboard-team-webhooks
       [:& team-webhooks-page {:team team}]

       :dashboard-team-settings
       [:& team-settings-page {:team team :profile profile}]

       nil)]))

(mf/defc dashboard
  [{:keys [route profile] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        section        (get-in route [:data :name])
        params         (parse-params route)

        project-id     (:project-id params)
        team-id        (:team-id params)
        search-term    (:search-term params)

        teams          (mf/deref refs/teams)
        team           (get teams team-id)

        projects       (mf/deref refs/dashboard-projects)
        project        (get projects project-id)]

    (hooks/use-shortcuts ::dashboard sc/shortcuts)

    (mf/with-effect [profile team-id]
      (st/emit! (dd/initialize {:id team-id}))
      (fn []
        (st/emit! (dd/finalize {:id team-id}))))

    (mf/with-effect []
      (let [key (events/listen goog/global "keydown"
                               (fn [event]
                                 (when (kbd/enter? event)
                                   (dom/stop-propagation event)
                                   (st/emit! (dd/open-selected-file)))))]
        (fn []
          (events/unlistenByKey key))))

    [:& (mf/provider ctx/current-team-id) {:value team-id}
     [:& (mf/provider ctx/current-project-id) {:value project-id}
      ;; NOTE: dashboard events and other related functions assumes
      ;; that the team is a implicit context variable that is
      ;; available using react context or accessing
      ;; the :current-team-id on the state. We set the key to the
      ;; team-id because we want to completely refresh all the
      ;; components on team change. Many components assumes that the
      ;; team is already set so don't put the team into mf/deps.
      (when team
        [:main {:class (dom/classnames :dashboard-layout (not new-css-system)
                                       :dashboard-layout-refactor new-css-system)
                :key (:id team)}
         [:& sidebar
          {:team team
           :projects projects
           :project project
           :profile profile
           :section section
           :search-term search-term}]
         (when (and team profile (seq projects))
           [:& dashboard-content
            {:projects projects
             :profile profile
             :project project
             :section section
             :search-term search-term
             :team team}])])]]))
