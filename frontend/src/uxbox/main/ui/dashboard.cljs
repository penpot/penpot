;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.dashboard
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.dashboard.sidebar :refer [sidebar]]
   [uxbox.main.ui.dashboard.search :refer [search-page]]
   [uxbox.main.ui.dashboard.project :refer [project-page]]
   [uxbox.main.ui.dashboard.recent-files :refer [recent-files-page]]
   [uxbox.main.ui.dashboard.library :refer [library-page]]
   [uxbox.main.ui.dashboard.profile :refer [profile-section]]
   [uxbox.main.ui.messages :refer [messages-widget]]))

(defn ^boolean uuid-str?
  [s]
  (and (string? s)
       (boolean (re-seq us/uuid-rx s))))

(defn- parse-params
  [route profile]
  (let [search-term (get-in route [:params :query :search-term])
        team-id (get-in route [:params :path :team-id])
        project-id (get-in route [:params :path :project-id])]
    (cond->
      {:search-term search-term}

      (uuid-str? team-id)
      (assoc :team-id (uuid team-id))

      (= "self" team-id)
      (assoc :team-id (:default-team-id profile))

      (uuid-str? project-id)
      (assoc :project-id (uuid project-id))

      (and (= "drafts" project-id)
           (= "self" team-id))
      (assoc :project-id (:default-project-id profile)))))


(mf/defc dashboard
  [{:keys [route] :as props}]
  (let [profile (mf/deref refs/profile)
        section (get-in route [:data :name])
        {:keys [search-term team-id project-id]} (parse-params route profile)]
    [:main.dashboard-main
     [:& messages-widget]
     [:section.dashboard-layout
      [:div.main-logo i/logo-icon]
      [:& profile-section {:profile profile}]
      [:& sidebar {:team-id team-id
                   :project-id project-id
                   :search-term search-term
                   :section section}]
      [:div.dashboard-content
       (case section
         :dashboard-search
         (mf/element search-page #js {:team-id team-id :search-term search-term})

         :dashboard-team
         (mf/element recent-files-page #js {:team-id team-id})

         :dashboard-library
         (mf/element library-page #js {:team-id team-id})

         :dashboard-project
         (mf/element project-page #js {:team-id team-id
                                       :project-id project-id}))]]])
  )
