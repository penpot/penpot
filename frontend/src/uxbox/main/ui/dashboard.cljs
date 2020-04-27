;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

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
   [uxbox.main.ui.messages :refer [messages]]))

(defn ^boolean uuid-str?
  [s]
  (and (string? s)
       (boolean (re-seq us/uuid-rx s))))

(defn- parse-params
  [route profile]
  (let [search-term (get-in route [:params :query :search-term])
        route-name (get-in route [:data :name])
        team-id (get-in route [:params :path :team-id])
        project-id (get-in route [:params :path :project-id])
        library-id (get-in route [:params :path :library-id])]
    (cond->
      {:search-term search-term}

      (uuid-str? team-id)
      (assoc :team-id (uuid team-id))

      (uuid-str? project-id)
      (assoc :project-id (uuid project-id))

      (= "drafts" project-id)
      (assoc :project-id (:default-project-id profile))

      (str/starts-with? (name route-name) "dashboard-library")
      (assoc :library-section (get-in route [:data :section]))

      (uuid-str? library-id)
      (assoc :library-id (uuid library-id)))))


(mf/defc dashboard
  [{:keys [route] :as props}]
  (let [profile (mf/deref refs/profile)
        page (get-in route [:data :name])
        {:keys [search-term team-id project-id library-id library-section] :as params}
        (parse-params route profile)]
    [:*
     [:& messages]
     [:section.dashboard-layout
      [:div.main-logo i/logo-icon]
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

         (:dashboard-library-icons
          :dashboard-library-icons-index
          :dashboard-library-images
          :dashboard-library-images-index
          :dashboard-library-palettes
          :dashboard-library-palettes-index)
         [:& library-page {:key (str library-id)
                           :team-id team-id
                           :library-id library-id
                           :section library-section}]

         :dashboard-project
         [:& project-page {:team-id team-id
                           :project-id project-id}])]]]))
