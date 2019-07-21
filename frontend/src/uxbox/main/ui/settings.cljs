;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings
  (:require
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.main.ui.settings.header :refer [header]]
   [uxbox.main.ui.settings.notifications :as notifications]
   [uxbox.main.ui.settings.password :as password]
   [uxbox.main.ui.settings.profile :as profile]))

(mf/defc settings
  {:wrap [mf/memo*]}
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])]
    [:main.dashboard-main
     (messages-widget)
     [:& header {:section section}]
     (case section
       :settings/profile (mf/elem profile/profile-page)
       :settings/password (mf/elem password/password-page)
       :settings/notifications (mf/elem notifications/notifications-page))]))




