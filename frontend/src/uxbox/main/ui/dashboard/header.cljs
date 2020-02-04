;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.header
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as rt]))

(declare user)

(mf/defc header-link
  [{:keys [section content] :as props}]
  (let [on-click #(st/emit! (rt/nav section))]
    [:a {:on-click on-click} content]))

(mf/defc header
  [{:keys [section] :as props}]
  (let [locale (i18n/use-locale)
        projects? (= section :dashboard-projects)
        icons? (= section :dashboard-icons)
        images? (= section :dashboard-images)
        colors? (= section :dashboard-colors)]
    [:header#main-bar.main-bar
     [:div.main-logo
      [:& header-link {:section :dashboard-projects
                       :content i/logo}]]
     [:ul.main-nav
      [:li {:class (when projects? "current")}
       [:& header-link {:section :dashboard-projects
                        :content (t locale "dashboard.header.projects")}]]
      [:li {:class (when icons? "current")}
       [:& header-link {:section :dashboard-icons
                        :content (t locale "dashboard.header.icons")}]]
      [:li {:class (when images? "current")}
       [:& header-link {:section :dashboard-images
                        :content (t locale "dashboard.header.images")}]]
      [:li {:class (when colors? "current")}
       [:& header-link {:section :dashboard-colors
                        :content (t locale "dashboard.header.colors")}]]]
     [:& user]]))


;; --- User Widget

(declare user-menu)
(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(mf/defc user
  [props]
  (let [open (mf/use-state false)
        profile (mf/deref profile-ref)
        photo (:photo-uri profile "")
        photo (if (str/empty? photo)
                "/images/avatar.jpg"
                photo)]
    [:div.user-zone {:on-click #(st/emit! (rt/nav :settings-profile))
                     :on-mouse-enter #(reset! open true)
                     :on-mouse-leave #(reset! open false)}
     [:span (:fullname profile)]
     [:img {:src photo}]
     (when @open
       [:& user-menu])]))

;; --- User Menu

(mf/defc user-menu
  [props]
  (let [locale (i18n/use-locale)
        on-click
        (fn [event section]
          (dom/stop-propagation event)
          (if (keyword? section)
            (st/emit! (rt/nav section))
            (st/emit! section)))]
    [:ul.dropdown
     [:li {:on-click #(on-click % :settings-profile)}
      i/user
      [:span (t locale "dashboard.header.user-menu.profile")]]
     [:li {:on-click #(on-click % :settings-password)}
      i/lock
      [:span (t locale "dashboard.header.user-menu.password")]]
     [:li {:on-click #(on-click % da/logout)}
      i/exit
      [:span (t locale "dashboard.header.user-menu.logout")]]]))

