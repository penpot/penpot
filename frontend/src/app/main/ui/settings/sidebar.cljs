;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.team :as dtm]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.dashboard.sidebar :refer [profile-section*]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private arrow-icon
  (i/icon-xref :arrow (stl/css :arrow-icon)))

(def ^:private feedback-icon
  (i/icon-xref :feedback (stl/css :feedback-icon)))

;; FIXME: move to common
(def ^:private go-settings-profile
  #(st/emit! (rt/nav :settings-profile)))

(def ^:private go-settings-feedback
  #(st/emit! (rt/nav :settings-feedback)))

(def ^:private go-settings-password
  #(st/emit! (rt/nav :settings-password)))

(def ^:private go-settings-options
  #(st/emit! (rt/nav :settings-options)))

(def ^:private go-settings-access-tokens
  #(st/emit! (rt/nav :settings-access-tokens)))

(def ^:private go-settings-notifications
  #(st/emit! (rt/nav :settings-notifications)))

(defn- show-release-notes
  [event]
  (let [version (:main cf/version)]
    (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))

    (if (and (kbd/alt? event) (kbd/mod? event))
      (st/emit! (modal/show {:type :onboarding}))
      (st/emit! (modal/show {:type :release-notes :version version})))))

(mf/defc sidebar-content
  {::mf/props :obj}
  [{:keys [profile section]}]
  (let [profile?       (= section :settings-profile)
        password?      (= section :settings-password)
        options?       (= section :settings-options)
        feedback?      (= section :settings-feedback)
        access-tokens? (= section :settings-access-tokens)
        notifications? (= section :settings-notifications)
        team-id        (or (dtm/get-last-team-id)
                           (:default-team-id profile))

        go-dashboard
        (mf/use-fn
         (mf/deps team-id)
         #(st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))]

    [:div {:class (stl/css :sidebar-content)}
     [:div {:class (stl/css :sidebar-content-section)}
      [:button {:class (stl/css :back-to-dashboard)
                :on-click go-dashboard}
       arrow-icon
       [:span {:class (stl/css :back-text)} (tr "labels.dashboard")]]]

     [:hr {:class (stl/css :sidebar-separator)}]

     [:div {:class (stl/css :sidebar-content-section)}
      [:ul {:class (stl/css :sidebar-nav-settings)}
       [:li {:class (stl/css-case :current profile?
                                  :settings-item true)
             :on-click go-settings-profile}
        [:span {:class (stl/css :element-title)} (tr "labels.profile")]]

       [:li {:class (stl/css-case :current password?
                                  :settings-item true)
             :on-click go-settings-password}
        [:span {:class (stl/css :element-title)} (tr "labels.password")]]

       [:li {:class (stl/css-case :current notifications?
                                  :settings-item true)
             :on-click go-settings-notifications}
        [:span {:class (stl/css :element-title)} (tr "labels.notifications")]]

       [:li {:class (stl/css-case :current options?
                                  :settings-item true)
             :on-click go-settings-options
             :data-testid "settings-profile"}
        [:span {:class (stl/css :element-title)} (tr "labels.settings")]]

       (when (contains? cf/flags :access-tokens)
         [:li {:class (stl/css-case :current access-tokens?
                                    :settings-item true)
               :on-click go-settings-access-tokens
               :data-testid "settings-access-tokens"}
          [:span {:class (stl/css :element-title)} (tr "labels.access-tokens")]])

       [:hr {:class (stl/css :sidebar-separator)}]

       [:li {:on-click show-release-notes :data-testid "release-notes"
             :class (stl/css :settings-item)}
        [:span {:class (stl/css :element-title)} (tr "labels.release-notes")]]

       (when (contains? cf/flags :user-feedback)
         [:li {:class (stl/css-case :current feedback?
                                    :settings-item true)
               :on-click go-settings-feedback}
          feedback-icon
          [:span {:class (stl/css :element-title)} (tr "labels.give-feedback")]])]]]))

(mf/defc sidebar
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [profile section]}]
  [:div {:class (stl/css :dashboard-sidebar :settings)}
   [:& sidebar-content {:profile profile
                        :section section}]
   [:> profile-section* {:profile profile}]])

