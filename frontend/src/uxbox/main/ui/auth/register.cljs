;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.register
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.data.auth :as uda]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.router :as rt]
            [uxbox.util.forms :as forms]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]))

;; --- Register Form

(def form-data (forms/focus-data :register st/state))
(def form-errors (forms/focus-errors :register st/state))
(def set-value! (partial forms/set-value! st/store :register))
(def set-error! (partial forms/set-error! st/store :register))

(def +register-form+
  {:username [forms/required forms/string]
   :fullname [forms/required forms/string]
   :email [forms/required forms/email]
   :password [forms/required forms/string]})

(mx/defc register-form
  {:mixins [mx/static mx/reactive
            (forms/clear-mixin st/store :register)]}
  []
  (let [data (mx/react form-data)
        errors (mx/react form-errors)
        valid? (forms/valid? data +register-form+)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (set-value! field value)))
            (on-error [{:keys [type code] :as payload}]
              (case code
                :uxbox.services.users/email-already-exists
                (set-error! :email "Email already exists")
                :uxbox.services.users/username-already-exists
                (set-error! :username "Username already exists")))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (uda/register data on-error)))]
      [:form {:on-submit on-submit}
       [:div.login-content
        [:input.input-text
         {:name "fullname"
          :value (:fullname data "")
          :on-change (partial on-change :fullname)
          :placeholder "Full Name"
          :type "text"}]
        (forms/input-error errors :fullname)

        [:input.input-text
         {:name "username"
          :value (:username data "")
          :on-change (partial on-change :username)
          :placeholder "Username"
          :type "text"}]
        (forms/input-error errors :username)

        [:input.input-text
         {:name "email"
          :ref "email"
          :value (:email data "")
          :on-change (partial on-change :email)
          :placeholder "Email"
          :type "text"}]
        (forms/input-error errors :email)

        [:input.input-text
         {:name "password"
          :ref "password"
          :value (:password data "")
          :on-change (partial on-change :password)
          :placeholder "Password"
          :type "password"}]
        (forms/input-error errors :password)

        [:input.btn-primary
         {:name "login"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :value "Register"
          :type "submit"}]
        [:div.login-links
         [:a {:on-click #(st/emit! (rt/navigate :auth/login))} "Already have an account?"]]]])))

;; --- Register Page

(mx/defc register-page
  {:mixins [mx/static]}
  [own]
  [:div.login
   [:div.login-body
    (messages-widget)
    [:a i/logo]
    (register-form)]])
