;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.auth.recovery-request
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [uxbox.util.router :as rt]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.util.schema :as us]
            [uxbox.main.data.auth :as uda]
            [uxbox.main.data.messages :as udm]
            [uxbox.main.data.forms :as udf]
            [uxbox.main.ui.forms :as forms]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]))

;; --- Recovery Request Constants

(def form-data
  (-> (l/in [:forms :recovery-request])
      (l/derive st/state)))

(def form-errors
  (-> (l/in [:errors :recovery-request])
      (l/derive st/state)))

(def set-value!
  (partial udf/assign-field-value :recovery-request))

;; --- Recovery Request Form

(def schema
  {:username [us/required us/string]})

(defn- form-render
  [own]
  (let [form (rum/react form-data)
        errors (rum/react form-errors)
        valid? (us/valid? form schema)]
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

(def form
  (mx/component
   {:render form-render
    :name "form"
    :mixins [mx/static rum/reactive]}))

;; --- Recovery Request Page

(defn- recovery-request-page-render
  [own]
  (html
   [:div.login
    [:div.login-body
     (uum/messages)
     [:a i/logo]
     (form)]]))

(def recovery-request-page
  (mx/component
   {:render recovery-request-page-render
    :name "recovery-request-page"
    :mixins [mx/static]}))
