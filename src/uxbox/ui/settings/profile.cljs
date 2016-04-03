;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.settings.profile
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.data.users :as udu]
            [uxbox.util.dom :as dom]))

;; --- Lentes

(def ^:const ^:private profile-l
  (-> (l/key :profile)
      (l/focus-atom st/state)))

;; --- Profile Form

(defn profile-form-render
  [own]
  (let [local (:rum/local own)
        profile (merge (rum/react profile-l)
                       (deref local))
        theme (get-in profile [:metadata :theme] "light")]
    (letfn [(on-theme-change [event]
              (let [value (dom/event->value event)]
                (println "on-theme-change" value)
                (swap! local assoc-in [:metadata :theme] value)))
            (on-field-change [field event]
              (let [value (dom/event->value event)]
                (swap! local assoc field value)))
            (on-submit [event]
              (rs/emit! (udu/update-profile profile)))]
      (html
       [:form.profile-form
        [:span.user-settings-label "Name, username and email"]
        [:input.input-text
         {:type "text"
          :on-change (partial on-field-change :fullname)
          :value (:fullname profile "")
          :placeholder "Your name"}]
        [:input.input-text
         {:type "text"
          :on-change (partial on-field-change :username)
          :value (:username profile "")
          :placeholder "Your username"}]
        [:input.input-text
         {:type "email"
          :on-change (partial on-field-change :email)
          :value (:email profile "")
          :placeholder "Your email"}]

        [:span.user-settings-label "Choose a color theme"]
        [:div.input-radio.radio-primary
         [:input {:type "radio"
                  :checked (= theme "light")
                  :on-change on-theme-change
                  :id "light-theme"
                  :name "theme"
                  :value "light"}]
         [:label {:for "light-theme"} "Light theme"]

         [:input {:type "radio"
                  :checked (= theme "dark")
                  :on-change on-theme-change
                  :id "dark-theme"
                  :name "theme"
                  :value "dark"}]
         [:label {:for "dark-theme"} "Dark theme"]

         [:input {:type "radio"
                  :checked (= theme "high-contrast")
                  :on-change on-theme-change
                  :id "high-contrast-theme"
                  :name "theme"
                  :value "high-contrast"}]
         [:label {:for "high-contrast-theme"} "High-contrast theme"]]

        [:input.btn-primary
         {:type "button"
          :on-click on-submit
          :value "Update settings"}]]))))

(def profile-form
  (mx/component
   {:render profile-form-render
    :name "profile-form"
    :mixins [(mx/local) rum/reactive mx/static]}))

;; --- Profile Page

(defn profile-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content.user-settings
     [:div.user-settings-nav
      [:ul.user-settings-nav-inside
       [:li.current {:on-click #(r/go :settings/profile)} "Profile"]
       [:li {:on-click #(r/go :settings/password)} "Password"]
       [:li {:on-click #(r/go :settings/notifications)} "Notifications"]]]
     [:section.user-settings-content
      [:span.user-settings-label "Your avatar"]
      [:form.avatar-form
       [:img {:src "images/favicon.png" :border "0"}]
       [:input {:type "file"}]]
      (profile-form)
      ]]]))

(def profile-page
  (mx/component
   {:render profile-page-render
    :name "profile-page"
    :mixins [mx/static]}))

