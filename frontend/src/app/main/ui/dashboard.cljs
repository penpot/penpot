;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.common.spec :as us]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.ui.dashboard.sidebar :refer [sidebar]]
   [app.main.ui.dashboard.search :refer [search-page]]
   [app.main.ui.dashboard.project :refer [project-page]]
   [app.main.ui.dashboard.recent-files :refer [recent-files-page]]
   [app.main.ui.dashboard.libraries :refer [libraries-page]]
   [app.main.ui.dashboard.profile :refer [profile-section]]
   [app.util.router :as rt]
   [app.util.i18n :as i18n :refer [t]]))

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
      (assoc :project-id (uuid project-id))

      ;; TODO: delete the usage of "drafts"

      (= "drafts" project-id)
      (assoc :project-id (:default-project-id profile)))))

(mf/defc dashboard
  [{:keys [route] :as props}]
  (let [profile (mf/deref refs/profile)
        page    (get-in route [:data :name])
        {:keys [search-term team-id project-id] :as params} (parse-params route profile)]
    [:section.dashboard-layout
     [:div.main-logo
      [:a {:on-click #(st/emit! (rt/nav :dashboard-team {:team-id team-id}))}
       i/logo-icon]]
     [:& profile-section {:profile profile}]
     [:& sidebar {:team-id team-id
                  :project-id project-id
                  :section page
                  :search-term search-term}]
     [:div.dashboard-content
      (case page
        :dashboard-search
        [:& search-page {:team-id team-id :search-term search-term}]

        :dashboard-team
        [:& recent-files-page {:team-id team-id}]

        :dashboard-libraries
        [:& libraries-page {:team-id team-id}]

        :dashboard-project
        [:& project-page {:team-id team-id
                          :project-id project-id}])]]))


