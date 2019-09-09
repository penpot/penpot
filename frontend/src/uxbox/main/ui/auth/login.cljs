;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.login
  (:require
   [rumext.alpha :as mf]
   [cljs.spec.alpha :as s]
   [uxbox.builtins.icons :as i]
   [uxbox.config :as cfg]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms2 :as fm2]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]))

(s/def ::username ::fm2/not-empty-string)
(s/def ::password ::fm2/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::username ::password]))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [{:keys [username password]} (:clean-data form)]
    (st/emit! (da/login {:username username
                         :password password}))))

(mf/defc demo-warning
  [_]
  [:div.message-inline
   [:p
    [:strong "WARNING: "] "this is a " [:strong "demo"] " service."
    [:br]
    [:strong "DO NOT USE"] " for real work, " [:br]
    " the projects will be periodicaly wiped."]])


(mf/defc login-form
  []
  (let [{:keys [data] :as form} (fm2/use-form ::login-form {})]
    (prn "login-form" form)
    [:form {:on-submit #(on-submit % form)}
     [:div.login-content
      (when cfg/isdemo
        [:& demo-warning])

      [:input.input-text
       {:name "username"
        :tab-index "2"
        :value (:username data "")
        :class (fm2/error-class form :username)
        :on-blur (fm2/on-input-blur form :username)
        :on-change (fm2/on-input-change form :username)
        :placeholder (tr "auth.email-or-username")
        :type "text"}]
      [:input.input-text
       {:name "password"
        :tab-index "3"
        :value (:password data "")
        :class (fm2/error-class form :password)
        :on-blur (fm2/on-input-blur form :password)
        :on-change (fm2/on-input-change form :password)
        :placeholder (tr "auth.password")
        :type "password"}]
      [:input.btn-primary
       {:name "login"
        :tab-index "4"
        :class (when-not (:valid form) "btn-disabled")
        :disabled (not (:valid form))
        :value (tr "auth.signin")
        :type "submit"}]

      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :auth/recovery-request))
            :tab-index "5"}
        (tr "auth.forgot-password")]
       [:a {:on-click #(st/emit! (rt/nav :auth/register))
            :tab-index "6"}
        (tr "auth.no-account")]]]]))

(mf/defc login-page
  []
  [:div.login
   [:div.login-body
    [:& messages-widget]
    [:a i/logo]
    [:& login-form]]])
