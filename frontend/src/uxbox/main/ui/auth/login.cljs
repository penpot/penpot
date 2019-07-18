;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.login
  (:require [cljs.spec.alpha :as s :include-macros true]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.builtins.icons :as i]
            [uxbox.config :as cfg]
            [uxbox.main.store :as st]
            [uxbox.main.data.auth :as da]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.dom :as dom]
            [uxbox.util.forms :as fm]
            [rumext.core :as mx :include-macros true]
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


(mx/defc login-form
  {:mixins [mx/static mx/reactive]}
  []
  (let [data (mx/react form-data)
        valid? (fm/valid? ::login-form data)]
    (letfn [(on-change [event field]
              (let [value (dom/event->value event)]
                (st/emit! (assoc-value field value))))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (da/login {:username (:username data)
                                   :password (:password data)})))]
      [:form {:on-submit on-submit}
       [:div.login-content
        (when cfg/isdemo
          [:div.message-inline
           [:p
            [:strong "WARNING: "] "this is a " [:strong "demo"] " service."
            [:br]
            [:strong "DO NOT USE"] " for real work, " [:br]
            " the projects will be periodicaly wiped."]])
        [:input.input-text
         {:name "email"
          :tab-index "2"
          :ref "email"
          :value (:username data "")
          :on-change #(on-change % :username)
          :placeholder (tr "auth.email-or-username")
          :type "text"}]
        [:input.input-text
         {:name "password"
          :tab-index "3"
          :ref "password"
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
         [:a {:on-click #(st/emit! (rt/navigate :auth/recovery-request))
              :tab-index "5"}
          (tr "auth.forgot-password")]
         [:a {:on-click #(st/emit! (rt/navigate :auth/register))
              :tab-index "6"}
          (tr "auth.no-account")]]]])))

(mx/defc login-page
  {:mixins [mx/static (fm/clear-mixin st/store :login)]
   :init (fn [own]
                 (when @st/auth-ref
                   (st/emit! (rt/navigate :dashboard/projects)))
                 own)}
  []
  [:div.login
   [:div.login-body
    (messages-widget)
    [:a i/logo]
    (login-form)]])
