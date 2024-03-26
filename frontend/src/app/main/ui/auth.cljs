;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-page]]
   [app.main.ui.auth.recovery :refer [recovery-page]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-page register-success-page register-validate-page]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc terms-login
  []
  (let [show-all?     (and cf/terms-of-service-uri cf/privacy-policy-uri)
        show-terms?   (some? cf/terms-of-service-uri)
        show-privacy? (some? cf/privacy-policy-uri)]

    (when show-all?
      [:div {:class (stl/css :terms-login)}
       (when show-terms?
         [:a {:href cf/terms-of-service-uri :target "_blank" :class (stl/css :auth-link)}
          (tr "auth.terms-of-service")])

       (when show-all?
         [:span {:class (stl/css :and-text)}
          (dm/str "  " (tr "labels.and") "  ")])

       (when show-privacy?
         [:a {:href cf/privacy-policy-uri :target "_blank" :class (stl/css :auth-link)}
          (tr "auth.privacy-policy")])])))

(mf/defc auth
  {::mf/props :obj}
  [{:keys [route]}]
  (let [section (dm/get-in route [:data :name])
        params  (:query-params route)
        error   (:error params)]

    (mf/with-effect []
      (dom/set-html-title (tr "title.default")))

    (mf/with-effect [error]
      (when error
        (st/emit! (du/show-redirect-error error))))

    [:main {:class (stl/css :auth-section)}
     [:a {:href "#/" :class (stl/css :logo-btn)} i/logo]
     [:div {:class (stl/css :login-illustration)}
      i/login-illustration]

     [:section {:class (stl/css :auth-content)}

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

      (when (= section :auth-register)
        [:& terms-login])]]))
