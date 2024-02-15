;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.sidebar
  (:require-macros [app.main.style :as stl])
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
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

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
        team-id        (du/get-current-team-id profile)

        go-dashboard
        (mf/use-fn
         (mf/deps team-id)
         #(st/emit! (rt/nav :dashboard-projects {:team-id team-id})))]

    [:div {:class (stl/css :sidebar-content)}
     [:div {:class (stl/css :sidebar-content-section)}
      [:div {:class (stl/css :back-to-dashboard)
             :on-click go-dashboard}
       [:span {:class (stl/css :icon)} i/arrow-down]
       [:span {:class (stl/css :text)} (tr "labels.dashboard")]]]

     [:hr]

     [:div {:class (stl/css :sidebar-content-section)}
      [:ul {:class (stl/css :sidebar-nav :no-overflow)}
       [:li {:class (when profile? (stl/css :current))
             :on-click go-settings-profile}
        [:span {:class (stl/css :element-title)} (tr "labels.profile")]]

       [:li {:class (when password? (stl/css :current))
             :on-click go-settings-password}
        [:span {:class (stl/css :element-title)} (tr "labels.password")]]

       [:li {:class (when options? (stl/css :current))
             :on-click go-settings-options
             :data-test "settings-profile"}
        [:span {:class (stl/css :element-title)} (tr "labels.settings")]]

       (when (contains? cf/flags :access-tokens)
         [:li {:class (when access-tokens? (stl/css :current))
               :on-click go-settings-access-tokens
               :data-test "settings-access-tokens"}
          [:span {:class (stl/css :element-title)} (tr "labels.access-tokens")]])

       [:hr]

       [:li {:on-click show-release-notes :data-test "release-notes"}
        [:span {:class (stl/css :element-title)} (tr "labels.release-notes")]]

       (when (contains? cf/flags :user-feedback)
         [:li {:class (when feedback? (stl/css :current))
               :on-click go-settings-feedback}
          i/feedback-refactor
          [:span {:class (stl/css :element-title)} (tr "labels.give-feedback")]])]]]))

(mf/defc sidebar
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [profile locale section]}]
  [:div {:class (stl/css :dashboard-sidebar :settings)}
   [:& sidebar-content {:profile profile
                        :section section}]
   [:& profile-section {:profile profile
                        :locale locale}]])

