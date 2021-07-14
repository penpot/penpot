;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth
  (:require
   [app.main.ui.auth.login :refer [login-page]]
   [app.main.ui.auth.recovery :refer [recovery-page]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-page register-success-page register-validate-page]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc auth
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        params  (:query-params route)]

    (mf/use-effect
      #(dom/set-html-title (tr "title.default")))

    [:div.auth
     [:section.auth-sidebar
      [:a.logo {:href "https://penpot.app"} i/logo]
      [:span.tagline (tr "auth.sidebar-tagline")]]

     [:section.auth-content
      (case section
        :auth-register
        [:& register-page {:params params}]

        :auth-register-validate
        [:& register-validate-page {:params params}]

        :auth-register-success
        [:& register-success-page {:params params}]

        :auth-login
        [:& login-page {:params params}]

        :auth-recovery-request
        [:& recovery-request-page]

        :auth-recovery
        [:& recovery-page {:params params}])

      [:div.terms-login
       [:a {:href "https://penpot.app/terms.html" :target "_blank"} "Terms of service"]
       [:span "and"]
       [:a {:href "https://penpot.app/privacy.html" :target "_blank"} "Privacy policy"]]]]))
