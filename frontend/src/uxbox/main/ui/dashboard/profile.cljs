;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.profile
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.dom :as dom]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as rt]))

;; --- Component: Profile

(mf/defc profile-section
  [{:keys [profile] :as props}]
  (let [show  (mf/use-state false)
        photo (:photo-uri profile "")
        photo (if (str/empty? photo)
                "/images/avatar.jpg"
                photo)

        locale (i18n/use-locale)
        on-click
        (fn [event section]
          (dom/stop-propagation event)
          (if (keyword? section)
            (st/emit! (rt/nav section))
            (st/emit! section)))]

    [:div.user-zone {:on-click #(reset! show true)}
     [:img {:src photo}]
     [:span (:fullname profile)]

     [:& dropdown {:on-close #(reset! show false)
                   :show @show}
      [:ul.profile-menu
       [:li {:on-click #(on-click % :settings-profile)}
        i/user
        [:span (t locale "dashboard.header.profile-menu.profile")]]
       [:li {:on-click #(on-click % :settings-password)}
        i/lock
        [:span (t locale "dashboard.header.profile-menu.password")]]
       [:li {:on-click #(on-click % da/logout)}
        i/exit
        [:span (t locale "dashboard.header.profile-menu.logout")]]]]]))
