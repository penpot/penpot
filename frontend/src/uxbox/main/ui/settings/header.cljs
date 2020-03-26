;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.settings.header
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.router :as rt]))

(mf/defc header
  [{:keys [section] :as props}]
  (let [profile? (= section :settings-profile)
        password? (= section :settings-password)
        locale (i18n/use-locale)]
    [:header
      [:div.main-logo {:on-click #(st/emit! (rt/nav :dashboard-team {:team-id "self"}))}
       i/logo-icon]
     [:section.main-bar
      [:nav
       [:a.nav-item
        {:class (when profile? "current")
         :on-click #(st/emit! (rt/nav :settings-profile))}
        (t locale "settings.profile")]
       [:a.nav-item
        {:class (when password? "current")
         :on-click #(st/emit! (rt/nav :settings-password))}
        (t locale "settings.password")]]]]))

