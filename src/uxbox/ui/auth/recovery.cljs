;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.auth.recovery
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [uxbox.router :as rt]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.schema :as us]
            [uxbox.data.auth :as uda]
            [uxbox.data.messages :as udm]
            [uxbox.data.forms :as udf]
            [uxbox.ui.forms :as forms]
            [uxbox.ui.icons :as i]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]))

;; --- Constants

(def recovery-request-form-data
  (-> (l/in [:forms :recovery-request])
      (l/focus-atom st/state)))

(def recovery-request-form-errors
  (-> (l/in [:errors :recovery-request])
      (l/focus-atom st/state)))

(def set-value!
  (partial udf/assign-field-value :recovery-request))

;; --- Recovery Request Form

(def recovery-request-schema
  {:username [us/required us/string]})

(defn- recovery-request-form-render
  [own]
  (let [form (rum/react recovery-request-form-data)
        errors (rum/react recovery-request-form-errors)
        valid? (us/valid? form recovery-request-schema)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (rs/emit! (set-value! field value))))
            (on-submit [event]
              (dom/prevent-default event)
              (rs/emit! (uda/recovery-request form)))]
      (html
       [:form {:on-submit on-submit}
        [:div.login-content
         [:input.input-text
          {:name "username"
           :value (:username form "")
           :on-change (partial on-change :username)
           :placeholder "username or email address"
           :type "text"}]
         (forms/input-error errors :username)

         [:input.btn-primary
          {:name "login"
           :class (when-not valid? "btn-disabled")
           :disabled (not valid?)
           :value "Recover password"
           :type "submit"}]
         [:div.login-links
          [:a {:on-click #(rt/go :auth/login)} "Go back!"]]]]))))

(def recovery-request-form
  (mx/component
   {:render recovery-request-form-render
    :name "recovery-request-form"
    :mixins [mx/static rum/reactive]}))

;; --- Recovery Request Page

(defn- recovery-request-page-render
  [own]
  (html
   [:div.login
    [:div.login-body
     (uum/messages)
     [:a i/logo]
     (recovery-request-form)]]))

(def recovery-request-page
  (mx/component
   {:render recovery-request-page-render
    :name "recovery-request-page"
    :mixins [mx/static]}))
