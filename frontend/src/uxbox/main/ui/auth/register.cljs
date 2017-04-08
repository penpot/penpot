;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.register
  (:require [cljs.spec :as s :include-macros true]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.dom :as dom]
            [uxbox.util.forms :as fm]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.router :as rt]))

(def form-data (fm/focus-data :register st/state))
(def form-errors (fm/focus-errors :register st/state))

(def assoc-value (partial fm/assoc-value :register))
(def assoc-error (partial fm/assoc-error :register))
(def clear-form (partial fm/clear-form :register))

;; TODO: add better password validation

(s/def ::username ::fm/non-empty-string)
(s/def ::fullname ::fm/non-empty-string)
(s/def ::password ::fm/non-empty-string)
(s/def ::email ::fm/email)

(s/def ::register-form
  (s/keys :req-un [::username
                   ::fullname
                   ::email
                   ::password]))

(mx/defc register-form
  {:mixins [mx/static mx/reactive
            (fm/clear-mixin st/store :register)]}
  []
  (let [data (mx/react form-data)
        errors (mx/react form-errors)
        valid? (fm/valid? ::register-form data)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (st/emit! (assoc-value field value))))
            (on-error [{:keys [type code] :as payload}]
              (case code
                :uxbox.services.users/email-already-exists
                (st/emit! (assoc-error :email "Email already exists"))
                :uxbox.services.users/username-already-exists
                (st/emit! (assoc-error :username "Username already exists"))))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (uda/register data on-error)))]
      [:form {:on-submit on-submit}
       [:div.login-content
        [:input.input-text
         {:name "fullname"
          :tab-index "2"
          :value (:fullname data "")
          :on-change (partial on-change :fullname)
          :placeholder "Full Name"
          :type "text"}]
        (fm/input-error errors :fullname)

        [:input.input-text
         {:name "username"
          :tab-index "3"
          :value (:username data "")
          :on-change (partial on-change :username)
          :placeholder "Username"
          :type "text"}]
        (fm/input-error errors :username)

        [:input.input-text
         {:name "email"
          :tab-index "4"
          :ref "email"
          :value (:email data "")
          :on-change (partial on-change :email)
          :placeholder "Email"
          :type "text"}]
        (fm/input-error errors :email)

        [:input.input-text
         {:name "password"
          :tab-index "5"
          :ref "password"
          :value (:password data "")
          :on-change (partial on-change :password)
          :placeholder "Password"
          :type "password"}]
        (fm/input-error errors :password)

        [:input.btn-primary
         {:name "login"
          :tab-index "6"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :value "Get started"
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
