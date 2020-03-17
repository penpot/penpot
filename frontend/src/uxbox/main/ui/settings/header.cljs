;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.header
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.router :as rt]))

(mf/defc header-link
  [{:keys [section content] :as props}]
  (let [on-click #(st/emit! (rt/nav section))]
    [:a {:on-click on-click} content]))

(mf/defc header
  [{:keys [section] :as props}]
  (let [profile? (= section :settings-profile)
        password? (= section :settings-password)]
    [:header#main-bar.main-bar
     [:div.main-logo
      [:& header-link {:section :dashboard-projects
                       :content i/logo}]]
     [:ul.main-nav
      [:li {:class (when profile? "current")}
       [:& header-link {:section :settings-profile
                        :content (tr "settings.profile")}]]
      [:li {:class (when password? "current")}
       [:& header-link {:section :settings-password
                        :content (tr "settings.password")}]]]
     #_[:& user]]))

