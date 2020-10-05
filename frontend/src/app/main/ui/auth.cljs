;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.auth
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.auth :as da]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-page]]
   [app.main.ui.auth.recovery :refer [recovery-page]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-page]]
   [app.main.ui.icons :as i]
   [app.util.forms :as fm]
   [app.util.storage :refer [cache]]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(mf/defc goodbye-page
  [{:keys [locale] :as props}]
  [:div.goodbay
   [:h1 (t locale "auth.goodbye-title")]])

(mf/defc auth
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        locale  (mf/deref i18n/locale)
        params  (:query-params route)]

    [:div.auth
     [:section.auth-sidebar
      [:a.logo {:href "/#/"} i/logo]
      [:span.tagline (t locale "auth.sidebar-tagline")]]

     [:section.auth-content
      (case section
        :auth-register [:& register-page {:locale locale :params params}]
        :auth-login    [:& login-page {:locale locale :params params}]
        :auth-goodbye  [:& goodbye-page {:locale locale}]
        :auth-recovery-request [:& recovery-request-page {:locale locale}]
        :auth-recovery [:& recovery-page {:locale locale
                                          :params (:query-params route)}])]]))
