;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-page]]
   [app.main.ui.auth.recovery :refer [recovery-page]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-page register-success-page]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(mf/defc auth
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        locale  (mf/deref i18n/locale)
        params  (:query-params route)]

    (mf/use-effect
      #(dom/set-html-title (t locale "title.default")))

    [:div.auth
     [:section.auth-sidebar
      [:a.logo {:href "https://penpot.app"} i/logo]
      [:span.tagline (t locale "auth.sidebar-tagline")]]

     [:section.auth-content
      (case section
        :auth-register
        [:& register-page {:locale locale :params params}]

        :auth-register-success
        [:& register-success-page {:params params}]

        :auth-login
        [:& login-page {:params params}]

        :auth-recovery-request
        [:& recovery-request-page {:locale locale}]

        :auth-recovery
        [:& recovery-page {:locale locale
                           :params (:query-params route)}])
      [:div.terms-login
       [:a {:href "https://penpot.app/terms.html" :target "_blank"} "Terms of service"]
       [:span "and"]
       [:a {:href "https://penpot.app/privacy.html" :target "_blank"} "Privacy policy"]]]]))
