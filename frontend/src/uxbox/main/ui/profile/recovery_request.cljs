;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

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
   [uxbox.util.i18n :as i18n]
   [uxbox.util.router :as rt]))

(s/def ::username ::us/not-empty-string)
(s/def ::recovery-request-form (s/keys :req-un [::username]))

(mf/defc recovery-form
  []
  (let [{:keys [data] :as form} (fm/use-form ::recovery-request-form {})
        tr (i18n/use-translations)
        on-success
        (fn []
          (st/emit! (um/info (tr "profile.recovery.recovery-token-sent"))
                    (rt/nav :profile-recovery)))

        on-submit
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (uda/request-profile-recovery (:clean-data form) on-success)))]
    [:form {:on-submit on-submit}
     [:div.login-content
      [:input.input-text
       {:name "username"
        :value (:username data "")
        :class (fm/error-class form :username)
        :on-blur (fm/on-input-blur form :username)
        :on-change (fm/on-input-change form :username)
        :placeholder (tr "profile.recovery.username-or-email")
        :type "text"}]
      [:input.btn-primary
       {:name "login"
        :class (when-not (:valid form) "btn-disabled")
        :disabled (not (:valid form))
        :value (tr "profile.recovery.submit-request")
        :type "submit"}]

      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :login))}
        (tr "profile.recovery.go-to-login")]]]]))

;; --- Recovery Request Page

(mf/defc profile-recovery-request-page
  []
  [:div.login
   [:div.login-body
    [:& messages-widget]
    [:a i/logo]
    [:& recovery-form]]])
