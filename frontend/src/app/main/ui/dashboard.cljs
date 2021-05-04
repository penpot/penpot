;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.files :refer [files-section]]
   [app.main.ui.dashboard.libraries :refer [libraries-page]]
   [app.main.ui.dashboard.projects :refer [projects-section]]
   [app.main.ui.dashboard.fonts :refer [fonts-page font-providers-page]]
   [app.main.ui.dashboard.search :refer [search-page]]
   [app.main.ui.dashboard.sidebar :refer [sidebar]]
   [app.main.ui.dashboard.team :refer [team-settings-page team-members-page]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(defn ^boolean uuid-str?
  [s]
  (and (string? s)
       (boolean (re-seq us/uuid-rx s))))

(defn- parse-params
  [route profile]
  (let [route-name  (get-in route [:data :name])
        search-term (get-in route [:params :query :search-term])
        team-id     (get-in route [:params :path :team-id])
        project-id  (get-in route [:params :path :project-id])]
    (cond->
      {:search-term search-term}

      (uuid-str? team-id)
      (assoc :team-id (uuid team-id))

      (uuid-str? project-id)
      (assoc :project-id (uuid project-id)))))

(defn- team-ref
  [id]
  (l/derived (l/in [:teams id]) st/state))

(defn- projects-ref
  [team-id]
  (l/derived (l/in [:projects team-id]) st/state))

(mf/defc dashboard-content
  [{:keys [team projects project section search-term profile] :as props}]
  [:div.dashboard-content {:on-click (st/emitf (dd/clear-selected-files))}
   (case section
     :dashboard-projects
     [:& projects-section {:team team :projects projects}]

     :dashboard-fonts
     [:& fonts-page {:team team}]

     :dashboard-font-providers
     [:& font-providers-page {:team team}]

     :dashboard-files
     (when project
       [:& files-section {:team team :project project}])

     :dashboard-search
     [:& search-page {:team team
                      :search-term search-term}]

     :dashboard-libraries
     [:& libraries-page {:team team}]

     :dashboard-team-members
     [:& team-members-page {:team team :profile profile}]

     :dashboard-team-settings
     [:& team-settings-page {:team team :profile profile}]

     nil)])

(mf/defc dashboard
  [{:keys [route] :as props}]
  (let [profile      (mf/deref refs/profile)
        section      (get-in route [:data :name])
        params       (parse-params route profile)

        project-id   (:project-id params)
        team-id      (:team-id params)
        search-term  (:search-term params)

        projects-ref (mf/use-memo (mf/deps team-id) #(projects-ref team-id))
        team-ref     (mf/use-memo (mf/deps team-id) #(team-ref team-id))

        team         (mf/deref team-ref)
        projects     (mf/deref projects-ref)
        project      (get projects project-id)]

    (mf/use-effect
     (mf/deps team-id)
     (st/emitf (dd/fetch-bundle {:id team-id})))

    (mf/use-effect
     (mf/deps)
     (fn []
       (let [props   (:props profile)
             version (:release-notes-viewed props)]
         (when (and (:onboarding-viewed props)
                    (not= version (:main @cf/version))
                    (not= "0.0" (:main @cf/version)))
           (tm/schedule 1000 #(st/emit! (modal/show {:type :release-notes :version (:main @cf/version)})))))))

    [:& (mf/provider ctx/current-file-id) {:value nil}
     [:& (mf/provider ctx/current-team-id) {:value team-id}
      [:& (mf/provider ctx/current-project-id) {:value project-id}
       [:& (mf/provider ctx/current-page-id) {:value nil}

        [:section.dashboard-layout
         [:& sidebar
          {:team team
           :projects projects
           :project project
           :profile profile
           :section section
           :search-term search-term}]
         (when (and team (seq projects))
           [:& dashboard-content
            {:projects projects
             :profile profile
             :project project
             :section section
             :search-term search-term
             :team team}])]]]]]))

