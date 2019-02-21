;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.recovery-request
  (:require [cljs.spec :as s :include-macros true]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.dom :as dom]
            [uxbox.util.forms :as fm]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.router :as rt]))

(def form-data (fm/focus-data :recovery-request st/state))
(def form-errors (fm/focus-errors :recovery-request st/state))

(def assoc-value (partial fm/assoc-value :profile-password))
(def assoc-errors (partial fm/assoc-errors :profile-password))
(def clear-form (partial fm/clear-form :profile-password))

(s/def ::username ::fm/non-empty-string)
(s/def ::recovery-request-form (s/keys :req-un [::username]))

(mx/defc recovery-request-form
  {:mixins [mx/static mx/reactive]}
  []
  (let [data (mx/react form-data)
        valid? (fm/valid? ::recovery-request-form data)]
    (letfn [(on-change [event]
              (let [value (dom/event->value event)]
                (st/emit! (assoc-value :username value))))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (uda/recovery-request data)
                        (clear-form)))]
      [:form {:on-submit on-submit}
       [:div.login-content
        [:input.input-text
         {:name "username"
          :value (:username data "")
          :on-change on-change
          :placeholder (tr "recovery-request.username-or-email.placeholder")
          :type "text"}]
        [:input.btn-primary
         {:name "login"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :value (tr "recovery-request.recover-password")
          :type "submit"}]
        [:div.login-links
         [:a {:on-click #(st/emit! (rt/navigate :auth/login))} (tr "recovery-request.go-back")]]]])))

;; --- Recovery Request Page

(mx/defc recovery-request-page
  {:mixins [mx/static (fm/clear-mixin st/store :recovery-request)]}
  []
  [:div.login
   [:div.login-body
    (messages-widget)
    [:a i/logo]
    (recovery-request-form)]])
