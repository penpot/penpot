;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.profile.recovery-request
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.spec :as us]
   [uxbox.main.data.auth :as uda]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.messages :as um]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as rt]))

(s/def ::email ::us/email)
(s/def ::recovery-request-form (s/keys :req-un [::email]))

(mf/defc recovery-form
  []
  (let [{:keys [data] :as form} (fm/use-form ::recovery-request-form {})
        locale (i18n/use-locale)

        on-success
        (fn []
          (st/emit! (um/info (t locale "profile.recovery.recovery-token-sent"))
                    (rt/nav :profile-recovery)))

        on-submit
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (uda/request-profile-recovery (:clean-data form) on-success)))]
    [:form {:on-submit on-submit}
     [:div.login-content
      [:input.input-text
       {:name "email"
        :value (:email data "")
        :class (fm/error-class form :email)
        :on-blur (fm/on-input-blur form :email)
        :on-change (fm/on-input-change form :email)
        :placeholder (t locale "profile.recovery.email")
        :type "text"}]
      [:input.btn-primary
       {:name "login"
        :class (when-not (:valid form) "btn-disabled")
        :disabled (not (:valid form))
        :value (t locale "profile.recovery.submit-request")
        :type "submit"}]

      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :login))}
        (t locale "profile.recovery.go-to-login")]]]]))

;; --- Recovery Request Page

(mf/defc profile-recovery-request-page
  []
  [:div.login
   [:div.login-body
    [:& messages-widget]
    [:a i/logo]
    [:& recovery-form]]])
