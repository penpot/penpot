;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.settings
  (:require
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.settings.options :refer [options-page]]
   [app.main.ui.settings.feedback :refer [feedback-page]]
   [app.main.ui.settings.password :refer [password-page]]
   [app.main.ui.settings.profile :refer [profile-page]]
   [app.main.ui.settings.sidebar :refer [sidebar]]
   [app.main.ui.settings.change-email]
   [app.main.ui.settings.delete-account]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  []
  (let [logout (constantly nil)]
    [:header.dashboard-header
     [:div.dashboard-title
      [:h1 (tr "dashboard.your-account-title")]]
     [:a.btn-secondary.btn-small {:on-click logout}
      (tr "labels.logout")]]))

(mf/defc settings
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        profile (mf/deref refs/profile)
        locale  (mf/deref i18n/locale)]
    [:section.dashboard-layout
     [:& sidebar {:profile profile
                  :locale locale
                  :section section}]

     [:div.dashboard-content
      [:& header]
      [:section.dashboard-container
       (case section
         :settings-profile
         [:& profile-page {:locale locale}]

         :settings-feedback
         [:& feedback-page]

         :settings-password
         [:& password-page {:locale locale}]

         :settings-options
         [:& options-page {:locale locale}])]]]))

