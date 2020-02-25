;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.login
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [uxbox.common.spec :as us]
   [uxbox.builtins.icons :as i]
   [uxbox.config :as cfg]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [{:keys [email password]} (:clean-data form)]
    (st/emit! (da/login {:email email :password password}))))

(mf/defc demo-warning
  [_]
  [:div.message-inline
   [:p
    [:strong "WARNING: "]
    "This is a " [:strong "demo"] " service, "
    [:strong "DO NOT USE"] " for real work, "
    " the projects will be periodicaly wiped."]])

(mf/defc login-form
  []
  (let [{:keys [data] :as form} (fm/use-form ::login-form {})]
    [:form {:on-submit #(on-submit % form)}
     [:div.login-content
      (when cfg/demo-warning
        [:& demo-warning])

      [:input.input-text
       {:name "email"
        :tab-index "2"
        :value (:email data "")
        :class (fm/error-class form :email)
        :on-blur (fm/on-input-blur form :email)
        :on-change (fm/on-input-change form :email)
        :placeholder (tr "login.email")
        :type "text"}]
      [:input.input-text
       {:name "password"
        :tab-index "3"
        :value (:password data "")
        :class (fm/error-class form :password)
        :on-blur (fm/on-input-blur form :password)
        :on-change (fm/on-input-change form :password)
        :placeholder (tr "login.password")
        :type "password"}]
      [:input.btn-primary
       {:name "login"
        :tab-index "4"
        :class (when-not (:valid form) "btn-disabled")
        :disabled (not (:valid form))
        :value (tr "login.submit")
        :type "submit"}]

      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :profile-recovery-request))
            :tab-index "5"}
        (tr "login.forgot-password")]
       [:a {:on-click #(st/emit! (rt/nav :profile-register))
            :tab-index "6"}
        (tr "login.register")]
       [:a {:on-click #(st/emit! da/create-demo-profile)
            :tab-index "7"
            :title (tr "login.create-demo-profile-description")}
        (tr "login.create-demo-profile")]]]]))

(mf/defc login-page
  []
  [:div.login
   [:div.login-body
    [:& messages-widget]
    [:a i/logo]
    [:& login-form]]])
