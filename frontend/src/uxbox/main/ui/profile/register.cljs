;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.profile.register
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :as uda]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages]]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]))

(s/def ::fullname ::fm/not-empty-string)
(s/def ::password ::fm/not-empty-string)
(s/def ::email ::fm/email)

(s/def ::register-form
  (s/keys :req-un [::password
                   ::fullname
                   ::email]))

(defn- on-error
  [error form]
  (case (:code error)
    :uxbox.services.users/registration-disabled
    (st/emit! (tr "errors.api.form.registration-disabled"))

    :uxbox.services.users/email-already-exists
    (swap! form assoc-in [:errors :email]
           {:type ::api
            :message "errors.api.form.email-already-exists"})

    (st/emit! (tr "errors.api.form.unexpected-error"))))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [data (:clean-data form)
        on-error #(on-error % form)]
    (st/emit! (uda/register data on-error))))

(mf/defc register-form
  [props]
  (let [{:keys [data] :as form} (fm/use-form ::register-form {})]
    [:form {:on-submit #(on-submit % form)}
     [:div.login-content
      [:input.input-text
       {:name "fullname"
        :tab-index "1"
        :value (:fullname data "")
        :class (fm/error-class form :fullname)
        :on-blur (fm/on-input-blur form :fullname)
        :on-change (fm/on-input-change form :fullname)
        :placeholder (tr "profile.register.fullname")
        :type "text"}]

      [:& fm/field-error {:form form
                          :type #{::api}
                          :field :fullname}]

      [:input.input-text
       {:type "email"
        :name "email"
        :tab-index "3"
        :class (fm/error-class form :email)
        :on-blur (fm/on-input-blur form :email)
        :on-change (fm/on-input-change form :email)
        :value (:email data "")
        :placeholder (tr "profile.register.email")}]

      [:& fm/field-error {:form form
                          :type #{::api}
                          :field :email}]


      [:input.input-text
       {:name "password"
        :tab-index "4"
        :value (:password data "")
        :class (fm/error-class form :password)
        :on-blur (fm/on-input-blur form :password)
        :on-change (fm/on-input-change form :password)
        :placeholder (tr "profile.register.password")
        :type "password"}]

      [:& fm/field-error {:form form
                          :type #{::api}
                          :field :email}]

      [:input.btn-primary
       {:type "submit"
        :tab-index "5"
        :class (when-not (:valid form) "btn-disabled")
        :disabled (not (:valid form))
        :value (tr "profile.register.get-started")}]

      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :login))}
        (tr "profile.register.already-have-account")]]]]))

;; --- Register Page

(mf/defc profile-register-page
  [props]
  [:div.login
   [:div.login-body
    [:& messages]
    [:a i/logo]
    [:& register-form]]])
