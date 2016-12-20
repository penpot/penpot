;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.login
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.auth :as da]
            [uxbox.main.data.messages :as udm]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.router :as rt]
            [uxbox.util.dom :as dom]
            [uxbox.util.forms :as forms]))

(def form-data (forms/focus-data :login st/state))
(def set-value! (partial forms/set-value! :login))

(defn- login-page-will-mount
  [own]
  (when @st/auth-ref
    (rt/go :dashboard/projects))
  own)

(def +login-form+
  {:email [forms/required forms/string]
   :password [forms/required forms/string]})

(mx/defc login-form
  {:mixins [mx/static mx/reactive]}
  []
  (let [data (mx/react form-data)
        valid? (forms/valid? data +login-form+)]
    (letfn [(on-change [event field]
              (let [value (dom/event->value event)]
                (set-value! field value)))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (da/login {:username (:email data)
                                   :password (:password data)})))]
      [:form {:on-submit on-submit}
       [:div.login-content
        [:input.input-text
         {:name "email"
          :ref "email"
          :value (:email data "")
          :on-change #(on-change % :email)
          :placeholder "Email or Username"
          :type "text"}]
        [:input.input-text
         {:name "password"
          :ref "password"
          :value (:password data "")
          :on-change #(on-change % :password)
          :placeholder "Password"
          :type "password"}]
        [:input.btn-primary
         {:name "login"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :value "Continue"
          :type "submit"}]
        [:div.login-links
         [:a {:on-click #(rt/go :auth/recovery-request)} "Forgot your password?"]
         [:a {:on-click #(rt/go :auth/register)} "Don't have an account?"]]]])))

(mx/defc login-page
  {:mixins [mx/static]
   :will-mount login-page-will-mount}
  []
  [:div.login
   [:div.login-body
    (uum/messages)
    [:a i/logo]
    (login-form)]])
