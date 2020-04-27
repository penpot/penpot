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
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.util.router :as rt]
   [uxbox.main.ui.dashboard.profile :refer [profile-section]]
   [uxbox.main.ui.messages :refer [messages]]
   [uxbox.main.ui.settings.header :refer [header]]
   [uxbox.main.ui.settings.password :refer [password-page]]
   [uxbox.main.ui.settings.profile :refer [profile-page]]))

(mf/defc settings
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        profile (mf/deref refs/profile)]
    [:main.settings-main
     [:& messages]
     [:div.settings-content
      [:& header {:section section :profile profile}]
      (case section
        :settings-profile (mf/element profile-page)
        :settings-password (mf/element password-page))]]))




