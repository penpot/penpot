;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.header
  (:require
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.main.ui.users :refer [user]]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as rt]))

(mf/defc header-link
  [{:keys [section content] :as props}]
  (let [on-click #(st/emit! (rt/nav section))]
    [:a {:on-click on-click} content]))

(mf/defc header
  [{:keys [section] :as props}]
  (let [projects? (= section :dashboard-projects)
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
                        :content (tr "ds.projects")}]]
      [:li {:class (when icons? "current")}
       [:& header-link {:section :dashboard-icons
                        :content (tr "ds.icons")}]]
      [:li {:class (when images? "current")}
       [:& header-link {:section :dashboard-images
                        :content (tr "ds.images")}]]
      [:li {:class (when colors? "current")}
       [:& header-link {:section :dashboard-colors
                        :content (tr "ds.colors")}]]]
     [:& user]]))


