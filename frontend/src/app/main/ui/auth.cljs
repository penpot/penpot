;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.auth :as da]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-page]]
   [app.main.ui.auth.recovery :refer [recovery-page]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-page register-success-page register-validate-page terms-register]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc auth
  {::mf/props :obj}
  [{:keys [route]}]
  (let [section (dm/get-in route [:data :name])
        is-register (or
                     (= section :auth-register)
                     (= section :auth-register-validate)
                     (= section :register-validate-page)
                     (= section :auth-register-success))
        params  (:query-params route)
        error   (:error params)]

    (mf/with-effect []
      (dom/set-html-title (tr "title.default")))

    (mf/with-effect [error]
      (when error
        (st/emit! (da/show-redirect-error error))))

    [:main {:class (stl/css-case
                    :auth-section true
                    :register is-register)}
     [:h1 {:class (stl/css :logo-container)}
      [:a {:href "#/" :title "Penpot" :class (stl/css :logo-btn)} i/logo]]
     [:div {:class (stl/css :login-illustration)}
      i/login-illustration]

     [:section {:class (stl/css :auth-content)}

      (case section
        :auth-register
        [:& register-page {:params params}]

        :auth-register-success
        [:& register-success-page {:params params}]

        :auth-register-validate
        [:& register-validate-page {:params params}]

        :auth-login
        [:& login-page {:params params}]

        :auth-recovery-request
        [:& recovery-request-page]

        :auth-recovery
        [:& recovery-page {:params params}])

      (when (= section :auth-register)
        [:& terms-register])]]))
