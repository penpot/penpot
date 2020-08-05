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
   [uxbox.main.ui.icons :as i]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.spec :as us]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.dashboard.sidebar :refer [sidebar]]
   [uxbox.main.ui.dashboard.search :refer [search-page]]
   [uxbox.main.ui.dashboard.project :refer [project-page]]
   [uxbox.main.ui.dashboard.recent-files :refer [recent-files-page]]
   [uxbox.main.ui.dashboard.libraries :refer [libraries-page]]
   ;; [uxbox.main.ui.dashboard.library :refer [library-page]]
   [uxbox.main.ui.dashboard.profile :refer [profile-section]]
   [uxbox.util.router :as rt]
   [uxbox.util.i18n :as i18n :refer [t]]))

(defn ^boolean uuid-str?
  [s]
  (and (string? s)
       (boolean (re-seq us/uuid-rx s))))

(defn- parse-params
  [route profile]
  (let [search-term (get-in route [:params :query :search-term])
        route-name (get-in route [:data :name])
        team-id (get-in route [:params :path :team-id])
        project-id (get-in route [:params :path :project-id])]
        ;; library-id (get-in route [:params :path :library-id])]
    (cond->
      {:search-term search-term}

      (uuid-str? team-id)
      (assoc :team-id (uuid team-id))

      (uuid-str? project-id)
      (assoc :project-id (uuid project-id))

      (= "drafts" project-id)
      (assoc :project-id (:default-project-id profile)))))

      ;; (str/starts-with? (name route-name) "dashboard-library")
      ;; (assoc :library-section (get-in route [:data :section]))
      ;;
      ;; (uuid-str? library-id)
      ;; (assoc :library-id (uuid library-id)))))

(declare global-notifications)


(mf/defc dashboard
  [{:keys [route] :as props}]
  (let [profile (mf/deref refs/profile)
        page (get-in route [:data :name])
        ;; {:keys [search-term team-id project-id library-id library-section] :as params}
        {:keys [search-term team-id project-id] :as params}
          (parse-params route profile)]
    [:*
     [:& global-notifications {:profile profile}]
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

         ;; (:dashboard-library-icons
         ;;  :dashboard-library-icons-index
         ;;  :dashboard-library-images
         ;;  :dashboard-library-images-index
         ;;  :dashboard-library-palettes
         ;;  :dashboard-library-palettes-index)
         ;; [:& library-page {:key (str library-id)
         ;;                   :team-id team-id
         ;;                   :library-id library-id
         ;;                   :section library-section}]

         :dashboard-project
         [:& project-page {:team-id team-id
                           :project-id project-id}])]]]))


(mf/defc global-notifications
  [{:keys [profile] :as props}]
  (let [locale  (mf/deref i18n/locale)]
    (when (and profile
               (not= uuid/zero (:id profile))
               (= (:pending-email profile)
                  (:email profile)))
    [:section.banner.error.quick
     [:div.content
      [:div.icon i/msg-warning]
      [:span (t locale "settings.notifications.email-not-verified" (:email profile))]]])))

