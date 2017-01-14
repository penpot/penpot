;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.recovery
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.router :as rt]
            [uxbox.util.forms :as forms]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]))


;; --- Recovery Form

(def form-data (forms/focus-data :recovery st/state))
(def set-value! (partial forms/set-value! st/store :recovery))

(def +recovery-form+
  {:password [forms/required forms/string]})

(mx/defc recovery-form
  {:mixins [mx/static mx/reactive]}
  [token]
  (let [data (merge (mx/react form-data)
                    {:token token})
        valid? (forms/valid? data +recovery-form+)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (set-value! field value)))
            (on-submit [event]
              (dom/prevent-default event)
              (st/emit! (uda/recovery data)
                        (forms/clear-form :recovery)
                        (forms/clear-errors :recovery)))]
      [:form {:on-submit on-submit}
       [:div.login-content
        [:input.input-text
         {:name "password"
          :value (:password data "")
          :on-change (partial on-change :password)
          :placeholder "Password"
          :type "password"}]
        [:input.btn-primary
         {:name "login"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :value "Recover password"
          :type "submit"}]
        [:div.login-links
         [:a {:on-click #(st/emit! (rt/navigate :auth/login))} "Go back!"]]]])))

;; --- Recovery Page

(defn- recovery-page-will-mount
  [own]
  (let [[token] (:rum/args own)]
    (st/emit! (uda/validate-recovery-token token))
    own))

(mx/defc recovery-page
  {:mixins [mx/static (forms/clear-mixin st/store :recovery)]
   :will-mount recovery-page-will-mount}
  [token]
  [:div.login
   [:div.login-body
    (messages-widget)
    [:a i/logo]
    (recovery-form token)]])
