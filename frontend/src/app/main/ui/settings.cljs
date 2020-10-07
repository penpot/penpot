;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.settings
  (:require
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.settings.options :refer [options-page]]
   [app.main.ui.settings.password :refer [password-page]]
   [app.main.ui.settings.profile :refer [profile-page]]
   [app.main.ui.settings.sidebar :refer [sidebar]]
   [app.main.ui.settings.change-email]
   [app.main.ui.settings.delete-account]
   [app.util.i18n :as i18n :refer [t]]
   [rumext.alpha :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  [{:keys [locale] :as props}]
  (let [logout (constantly nil)]
    [:header.dashboard-header
     [:h1.dashboard-title (t locale "dashboard.your-account-title")]
     [:a.btn-secondary.btn-small {:on-click logout}
      (t locale "labels.logout")]]))

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
      [:& header {:locale locale}]
      [:section.dashboard-container
       (case section
         :settings-profile
         [:& profile-page {:locale locale}]

         :settings-password
         [:& password-page {:locale locale}]

         :settings-options
         [:& options-page {:locale locale}])]]]))

