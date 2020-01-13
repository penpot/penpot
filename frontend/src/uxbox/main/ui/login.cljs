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

(s/def ::username ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)

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
  (let [{:keys [data] :as form} (fm/use-form ::login-form {})]
    [:form {:on-submit #(on-submit % form)}
     [:div.login-content
      (when cfg/isdemo
        [:& demo-warning])

      [:input.input-text
       {:name "username"
        :tab-index "2"
        :value (:username data "")
        :class (fm/error-class form :username)
        :on-blur (fm/on-input-blur form :username)
        :on-change (fm/on-input-change form :username)
        :placeholder (tr "auth.email-or-username")
        :type "text"}]
      [:input.input-text
       {:name "password"
        :tab-index "3"
        :value (:password data "")
        :class (fm/error-class form :password)
        :on-blur (fm/on-input-blur form :password)
        :on-change (fm/on-input-change form :password)
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
       [:a {:on-click #(st/emit! (rt/nav :profile-recovery-request))
            :tab-index "5"}
        (tr "auth.forgot-password")]
       [:a {:on-click #(st/emit! (rt/nav :profile-register))
            :tab-index "6"}
        (tr "auth.no-account")]]]]))

(mf/defc login-page
  []
  [:div.login
   [:div.login-body
    [:& messages-widget]
    [:a i/logo]
    [:& login-form]]])
