;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.sidebar
  (:require
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.dashboard.sidebar :refer [profile-section]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc sidebar-content
  [{:keys [profile section] :as props}]
  (let [profile?   (= section :settings-profile)
        password?  (= section :settings-password)
        options?   (= section :settings-options)
        feedback?  (= section :settings-feedback)

        go-dashboard
        (mf/use-callback
         (mf/deps profile)
         #(st/emit! (rt/nav :dashboard-projects {:team-id (du/get-current-team-id profile)})))

        go-settings-profile
        (mf/use-callback
         (mf/deps profile)
         #(st/emit! (rt/nav :settings-profile)))

        go-settings-feedback
        (mf/use-callback
         (mf/deps profile)
         #(st/emit! (rt/nav :settings-feedback)))

        go-settings-password
        (mf/use-callback
         (mf/deps profile)
         #(st/emit! (rt/nav :settings-password)))

        go-settings-options
        (mf/use-callback
         (mf/deps profile)
         #(st/emit! (rt/nav :settings-options)))

        show-release-notes
        (mf/use-callback
         (fn [event]
           (let [version (:main @cf/version)]
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes :version version}))))))]

    [:div.sidebar-content
     [:div.sidebar-content-section
      [:div.back-to-dashboard {:on-click go-dashboard}
       [:span.icon i/arrow-down]
       [:span.text (tr "labels.dashboard")]]]
     [:hr]

     [:div.sidebar-content-section
      [:ul.sidebar-nav.no-overflow
       [:li {:class (when profile? "current")
             :on-click go-settings-profile}
        i/user
        [:span.element-title (tr "labels.profile")]]

       [:li {:class (when password? "current")
             :on-click go-settings-password}
        i/lock
        [:span.element-title (tr "labels.password")]]

       [:li {:class (when options? "current")
             :on-click go-settings-options
             :data-test "settings-profile"}
        i/tree
        [:span.element-title (tr "labels.settings")]]

       [:hr]

       [:li {:on-click show-release-notes :data-test "release-notes"}
        i/pencil
        [:span.element-title (tr "labels.release-notes")]]

       (when (contains? @cf/flags :user-feedback)
         [:li {:class (when feedback? "current")
               :on-click go-settings-feedback}
          i/msg-info
          [:span.element-title (tr "labels.give-feedback")]])]]]))

(mf/defc sidebar
  {::mf/wrap [mf/memo]}
  [{:keys [profile locale section]}]
  [:div.dashboard-sidebar.settings
   [:div.sidebar-inside
    [:& sidebar-content {:profile profile
                         :section section}]
    [:& profile-section {:profile profile
                         :locale locale}]]])
