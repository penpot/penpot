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
            [uxbox.locales :as t :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui.icons :as i]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.data.users :as udu]
            [uxbox.ui.dashboard.header :refer (header)]))

;; --- Password Form

(defn password-form-render
  [own]
  (let [local (:rum/local own)
        invalid-reason (cond
                         (= 0 (count (:old-password @local))) "old-password-needed"
                         (= 0 (count (:password-1 @local))) "new-password-needed"
                         (> 6 (count (:password-1 @local ""))) "password-too-short"
                         (not= (:password-1 @local) (:password-2 @local)) "password-doesnt-match"
                         :else nil)
        valid? (nil? invalid-reason)]
    (letfn [(on-field-change [field event]
              (let [value (dom/event->value event)]
                (swap! local assoc field value)))
            (on-submit [event]
              (let [password (:password-1 @local)
                    old-password (:old-password @local)]
                (rs/emit! (udu/update-password old-password password))))]

      (html
       [:form.password-form
        (uum/messages)
        [:span.user-settings-label "Change password"]
        [:input.input-text
         {:type "password"
          :value (:old-password @local "")
          :on-change (partial on-field-change :old-password)
          :placeholder "Old password"}]
        [:input.input-text
         {:type "password"
          :value (:password-1 @local "")
          :on-change (partial on-field-change :password-1)
          :placeholder "New password"}]
        [:input.input-text
         {:type "password"
          :value (:password-2 @local "")
          :on-change (partial on-field-change :password-2)
          :placeholder "Confirm password"}]
        (when-not valid? [:span (tr invalid-reason)])
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

