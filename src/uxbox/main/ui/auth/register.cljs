;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.auth.register
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [uxbox.util.router :as rt]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.util.schema :as us]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.data.messages :as udm]
            [uxbox.main.data.forms :as udf]
            [uxbox.main.ui.forms :as forms]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]))

;; --- Constants

(def form-data
  (-> (l/in [:forms :register])
      (l/derive st/state)))

(def form-errors
  (-> (l/in [:errors :register])
      (l/derive st/state)))

(def set-value!
  (partial udf/assign-field-value :register))

;; --- Register Form

(defn- register-form-render
  [own]
  (let [form (rum/react form-data)
        errors (rum/react form-errors)
        valid? (us/valid? form uda/register-schema)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (rs/emit! (set-value! field value))))
            (on-submit [event]
              (dom/prevent-default event)
              (rs/emit! (uda/register form)))]
      (html
       [:form {:on-submit on-submit}
        [:div.login-content
         [:input.input-text
          {:name "fullname"
           :value (:fullname form "")
           :on-change (partial on-change :fullname)
           :placeholder "Full Name"
           :type "text"}]
         (forms/input-error errors :fullname)

         [:input.input-text
          {:name "username"
           :value (:username form "")
           :on-change (partial on-change :username)
           :placeholder "Username"
           :type "text"}]
         (forms/input-error errors :username)

         [:input.input-text
          {:name "email"
           :ref "email"
           :value (:email form "")
           :on-change (partial on-change :email)
           :placeholder "Email"
           :type "text"}]
         (forms/input-error errors :email)

         [:input.input-text
          {:name "password"
           :ref "password"
           :value (:password form "")
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
          ;; [:a {:on-click #(rt/go :auth/recover-password)} "Forgot your password?"]
          [:a {:on-click #(rt/go :auth/login)} "Already have an account?"]]]]))))

(def register-form
  (mx/component
   {:render register-form-render
    :name "register-form"
    :mixins [mx/static mx/reactive]}))

;; --- Register Page

(defn- register-page-render
  [own]
  (html
   [:div.login
    [:div.login-body
     (uum/messages)
     [:a i/logo]
     (register-form)]]))

(def register-page
  (mx/component
   {:render register-page-render
    :name "register-page"
    :mixins [mx/static]}))
