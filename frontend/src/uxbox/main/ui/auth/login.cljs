;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.login
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.config :as cfg]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages-widget]]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as rt]))

(def form-data (fm/focus-data :login st/state))
(def form-errors (fm/focus-errors :login st/state))

(def assoc-value (partial fm/assoc-value :login))
(def assoc-errors (partial fm/assoc-errors :login))
(def clear-form (partial fm/clear-form :login))

(s/def ::username ::fm/non-empty-string)
(s/def ::password ::fm/non-empty-string)

(s/def ::login-form
  (s/keys :req-un [::username ::password]))

(defn- on-change
  [event field]
  (let [value (dom/event->value event)]
    (st/emit! (assoc-value field value))))

(defn- on-submit
  [event data]
  (dom/prevent-default event)
  (st/emit! (da/login {:username (:username data)
                       :password (:password data)})))

(mf/defc demo-warning
  [_]
  [:div.message-inline
   [:p
    [:strong "WARNING: "] "this is a " [:strong "demo"] " service."
    [:br]
    [:strong "DO NOT USE"] " for real work, " [:br]
    " the projects will be periodicaly wiped."]])

(mf/defc login-form
  {:wrap [mf/reactive]}
  []
  (let [data (mf/react form-data)
        valid? (fm/valid? ::login-form data)]
    [:form {:on-submit #(on-submit % data)}
     [:div.login-content
      (when cfg/isdemo
        [:& demo-warning])
      [:input.input-text
       {:name "email"
        :tab-index "2"
        :value (:username data "")
        :on-change #(on-change % :username)
        :placeholder (tr "auth.email-or-username")
        :type "text"}]
      [:input.input-text
       {:name "password"
        :tab-index "3"
        :value (:password data "")
        :on-change #(on-change % :password)
        :placeholder (tr "auth.password")
        :type "password"}]
      [:input.btn-primary
       {:name "login"
        :tab-index "4"
        :class (when-not valid? "btn-disabled")
        :disabled (not valid?)
        :value (tr "auth.signin")
        :type "submit"}]
      [:div.login-links
       [:a {:on-click #(st/emit! (rt/nav :auth/recovery-request))
            :tab-index "5"}
        (tr "auth.forgot-password")]
       [:a {:on-click #(st/emit! (rt/nav :auth/register))
            :tab-index "6"}
        (tr "auth.no-account")]]]]))


;; {:mixins [mx/static (fm/clear-mixin st/store :login)]}

(mf/defc login-page
  []
  (mf/use-effect :end #(st/emit! (fm/clear-form :login)))
  [:div.login
   [:div.login-body
    (messages-widget)
    [:a i/logo]
    [:& login-form]]])
