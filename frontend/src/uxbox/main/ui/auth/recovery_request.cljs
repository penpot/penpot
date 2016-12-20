;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.recovery-request
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.router :as rt]
            [potok.core :as ptk]
            [uxbox.util.forms :as forms]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.store :as st]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.data.messages :as udm]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.ui.navigation :as nav]))

(def form-data (forms/focus-data :recovery-request st/state))
(def set-value! (partial forms/set-value! :recovery-request))

(def +recovery-request-form+
  {:username [forms/required forms/string]})

(mx/defc recovery-request-form
  {:mixins [mx/static mx/reactive]}
  []
  (let [data (mx/react form-data)
        valid? (forms/valid? data +recovery-request-form+)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (set-value! field value)))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (uda/recovery-request data)
                        (forms/clear :recovery-request)))]
      [:form {:on-submit on-submit}
       [:div.login-content
        [:input.input-text
         {:name "username"
          :value (:username data "")
          :on-change (partial on-change :username)
          :placeholder "username or email address"
          :type "text"}]
        [:input.btn-primary
         {:name "login"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :value "Recover password"
          :type "submit"}]
        [:div.login-links
         [:a {:on-click #(rt/go :auth/login)} "Go back!"]]]])))

;; --- Recovery Request Page

(mx/defc recovery-request-page
  {:mixins [mx/static]
   :will-unmount (forms/cleaner-fn :recovery-request)}
  []
  [:div.login
   [:div.login-body
    (uum/messages)
    [:a i/logo]
    (recovery-request-form)]])
