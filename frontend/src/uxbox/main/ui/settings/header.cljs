;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.settings.header
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.router :as rt]))

(mf/defc header
  [{:keys [section profile] :as props}]
  (let [profile?  (= section :settings-profile)
        password? (= section :settings-password)
        options?  (= section :settings-options)

        team-id   (:default-team-id profile)
        go-back   #(st/emit! (rt/nav :dashboard-team {:team-id team-id}))
        logout    #(st/emit! da/logout)

        locale    (mf/deref i18n/locale)
        team-id   (:default-team-id profile)]
    [:header
     [:section.secondary-menu
      [:div.left {:on-click go-back}
       [:span.icon i/arrow-slide]
       [:span.label "Dashboard"]]
      [:div.right {:on-click logout}
       [:span.label "Log out"]
       [:span.icon i/logout]]]
     [:h1 "Your account"]
     [:nav
      [:a.nav-item
       {:class (when profile? "current")
        :on-click #(st/emit! (rt/nav :settings-profile))}
       (t locale "settings.profile")]

      [:a.nav-item
       {:class (when password? "current")
        :on-click #(st/emit! (rt/nav :settings-password))}
       (t locale "settings.password")]

      [:a.nav-item
       {:class (when options? "current")
        :on-click #(st/emit! (rt/nav :settings-options))}
       (t locale "settings.options")]

      [:a.nav-item
       {:class "foobar"
        :on-click #(st/emit! (rt/nav :settings-profile))}
       (t locale "settings.teams")]]]))
