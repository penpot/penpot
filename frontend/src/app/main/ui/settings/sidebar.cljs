;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.settings.sidebar
  (:require
   [app.common.spec :as us]
   [app.main.data.auth :as da]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.sidebar :refer [profile-section]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [goog.functions :as f]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc sidebar-content
  [{:keys [locale profile section] :as props}]
  (let [profile?   (= section :settings-profile)
        password?  (= section :settings-password)
        options?   (= section :settings-options)

        go-dashboard
        (mf/use-callback
         (mf/deps profile)
         (st/emitf (rt/nav :dashboard-projects {:team-id (:default-team-id profile)})))

        go-settings-profile
        (mf/use-callback
         (mf/deps profile)
         (st/emitf (rt/nav :settings-profile)))

        go-settings-password
        (mf/use-callback
         (mf/deps profile)
         (st/emitf (rt/nav :settings-password)))

        go-settings-options
        (mf/use-callback
         (mf/deps profile)
         (st/emitf (rt/nav :settings-options)))]

    [:div.sidebar-content
     [:div.sidebar-content-section
      [:div.back-to-dashboard {:on-click go-dashboard}
       [:span.icon i/arrow-down]
       [:span.text "Dashboard"]]]
     [:hr]

     [:div.sidebar-content-section
      [:ul.sidebar-nav.no-overflow
       [:li {:class (when profile? "current")
             :on-click go-settings-profile}
        i/user
        [:span.element-title (t locale "dashboard.sidebar.profile")]]

       [:li {:class (when password? "current")
             :on-click go-settings-password}
        i/lock
        [:span.element-title (t locale "dashboard.sidebar.password")]]

       [:li {:class (when options? "current")
             :on-click go-settings-options}
        i/tree
        [:span.element-title (t locale "dashboard.sidebar.settings")]]]]]))

(mf/defc sidebar
  {::mf/wrap [mf/memo]}
  [{:keys [profile locale section]}]
  [:div.dashboard-sidebar.settings
   [:div.sidebar-inside
    [:& sidebar-content {:locale locale
                         :profile profile
                         :section section}]
    [:& profile-section {:profile profile
                         :locale locale}]]])
