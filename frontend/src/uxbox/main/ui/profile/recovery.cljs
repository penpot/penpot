;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.profile.recovery
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.common.spec :as us]
   [uxbox.main.data.auth :as uda]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages]]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as rt]))

(s/def ::token ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)
(s/def ::recovery-form (s/keys :req-un [::token ::password]))

(mf/defc recovery-form
  []
  (let [{:keys [data] :as form} (fm/use-form ::recovery-form {})
        locale (i18n/use-locale)

        on-success
        (fn []
          (st/emit! (dm/info (t locale "profile.recovery.password-changed"))
                    (rt/nav :login)))

        on-error
        (fn []
          (st/emit! (dm/error (t locale "profile.recovery.invalid-token"))))

        on-submit
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (uda/recover-profile (assoc (:clean-data form)
                                                :on-error on-error
                                                :on-success on-success))))]
    [:form {:on-submit on-submit}
     [:div.login-content
      [:input.input-text
       {:name "token"
        :value (:token data "")
        :class (fm/error-class form :token)
        :on-blur (fm/on-input-blur form :token)
        :on-change (fm/on-input-change form :token)
        :placeholder (t locale "profile.recovery.token")
        :auto-complete "off"
        :type "text"}]
      [:input.input-text
       {:name "password"
        :value (:password data "")
        :class (fm/error-class form :password)
        :on-blur (fm/on-input-blur form :password)
        :on-change (fm/on-input-change form :password)
        :placeholder (t locale "profile.recovery.password")
        :type "password"}]
      [:input.btn-primary
       {:name "recover"
        :class (when-not (:valid form) "btn-disabled")
        :disabled (not (:valid form))
        :value (t locale "profile.recovery.submit-recover")
        :type "submit"}]

      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :login))}
        (t locale "profile.recovery.go-to-login")]]]]))

;; --- Recovery Request Page

(mf/defc profile-recovery-page
  []
  [:div.login
   [:div.login-body
    [:& messages]
    [:a i/logo]
    [:& recovery-form]]])
