;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.dashboard.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.modal :refer [modal-container*]]
   [app.main.ui.settings.access-tokens :refer [access-tokens-page]]
   [app.main.ui.settings.change-email]
   [app.main.ui.settings.delete-account]
   [app.main.ui.settings.feedback :refer [feedback-page]]
   [app.main.ui.settings.notifications :refer [notifications-page*]]
   [app.main.ui.settings.options :refer [options-page]]
   [app.main.ui.settings.password :refer [password-page]]
   [app.main.ui.settings.profile :refer [profile-page]]
   [app.main.ui.settings.sidebar :refer [sidebar]]
   [app.main.ui.settings.subscription :refer [subscription-page*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc header
  {::mf/wrap [mf/memo]}
  []
  [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
   [:div {:class (stl/css :dashboard-title)}
    [:h1 {:data-testid "account-title"} (tr "dashboard.your-account-title")]]])

(mf/defc settings
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        profile (mf/deref refs/profile)]

    (hooks/use-shortcuts ::dashboard sc/shortcuts)

    (mf/with-effect [profile]
      (when (nil? profile)
        (st/emit! (rt/assign-exception {:type :authentication}))))

    [:*
     [:> modal-container*]
     [:section {:class (stl/css :dashboard-layout-refactor :dashboard)}


      [:& sidebar {:profile profile
                   :section section}]

      [:div {:class (stl/css :dashboard-content)}
       [:& header]
       [:section {:class (stl/css :dashboard-container)}
        (case section
          :settings-profile
          [:& profile-page]

          :settings-feedback
          [:& feedback-page]

          :settings-password
          [:& password-page]

          :settings-options
          [:& options-page]

          :settings-subscription
          [:> subscription-page* {:profile profile}]

          :settings-access-tokens
          [:& access-tokens-page]

          :settings-notifications
          [:& notifications-page* {:profile profile}])]]]]))
