;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.auth.recovery
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.router :as rt]
            [uxbox.store :as st]
            [potok.core :as ptk]
            [uxbox.util.forms :as forms]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.data.messages :as udm]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.ui.navigation :as nav]))

;; --- Recovery Form

(def form-data (forms/focus-data :recovery st/state))
(def set-value! (partial forms/set-value! :recovery))

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
                        (forms/clear :recovery)))]
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
         [:a {:on-click #(rt/go :auth/login)} "Go back!"]]]])))

;; --- Recovery Page

(defn- recovery-page-will-mount
  [own]
  (let [[token] (:rum/args own)]
    (st/emit! (uda/validate-recovery-token token))
    own))

(mx/defc recovery-page
  {:mixins [mx/static]
   :will-mount recovery-page-will-mount
   :will-unmount (forms/cleaner-fn :recovery)}
  [token]
  [:div.login
   [:div.login-body
    (uum/messages)
    [:a i/logo]
    (recovery-form token)]])
