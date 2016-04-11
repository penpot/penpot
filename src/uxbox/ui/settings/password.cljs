;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.settings.password
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.schema :as sc]
            [uxbox.locales :as t :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.data.users :as udu]
            [uxbox.ui.icons :as i]
            [uxbox.ui.form :as form]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.util.dom :as dom]))

;; --- Password Form

(def password-form-schema
  [[:password-1 sc/required sc/string [sc/min-len 6]]
   [:password-2 sc/required sc/string
    [sc/identical-to :password-1 :message "errors.form.password-not-match"]]
   [:old-password sc/required sc/string]])

(defn field-errors
  [errors field]
  (when-let [errors (get errors field)]
    (html
     [:ul
      (for [error errors]
        [:li {:key error} error])])))

(defn password-form-render
  [own]
  (let [local (:rum/local own)
        form (:form @local)
        errors (:errors @local)
        valid? true #_(nil? invalid-reason)]
    (letfn [(on-field-change [field event]
              (let [value (dom/event->value event)]
                (swap! local assoc-in [:form field] value)))
            (on-submit [event]
              (when-let [data (form/validate! local password-form-schema)]
                (let [params {:password (:password-1 form)
                              :old-password (:old-password form)}]
                  (rs/emit! (udu/update-password params)))))]
      (html
       [:form.password-form
        [:span.user-settings-label "Change password"]
        [:input.input-text
         {:type "password"
          :class (form/error-class local :old-password)
          :value (:old-password form "")
          :on-change (partial on-field-change :old-password)
          :placeholder "Old password"}]
        (form/input-error local :old-password)
        [:input.input-text
         {:type "password"
          :class (form/error-class local :password-1)
          :value (:password-1 form "")
          :on-change (partial on-field-change :password-1)
          :placeholder "New password"}]
        (form/input-error local :password-1)
        [:input.input-text
         {:type "password"
          :class (form/error-class local :password-2)
          :value (:password-2 form "")
          :on-change (partial on-field-change :password-2)
          :placeholder "Confirm password"}]
        (form/input-error local :password-2)
        [:input.btn-primary
         {:type "button"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :on-click on-submit
          :value "Update settings"}]]))))

(def password-form
  (mx/component
   {:render password-form-render
    :name "password-form"
    :mixins [mx/static (mx/local)]}))

;; --- Password Page

(defn password-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    (uum/messages)
    [:section.dashboard-content.user-settings
     [:div.user-settings-nav
      [:ul.user-settings-nav-inside
       [:li {:on-click #(r/go :settings/profile)} "Profile"]
       [:li.current {:on-click #(r/go :settings/password)} "Password"]
       [:li {:on-click #(r/go :settings/notifications)} "Notifications"]]]

     [:section.user-settings-content
      (password-form)]]]))

(def password-page
  (mx/component
   {:render password-page-render
    :name "password-page"
    :mixins [mx/static]}))

