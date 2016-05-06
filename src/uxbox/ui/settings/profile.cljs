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
            [uxbox.schema :as sc]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.forms :as forms]
            [uxbox.ui.dashboard.header :refer (header)]
            [uxbox.ui.messages :as uum]
            [uxbox.data.users :as udu]
            [uxbox.data.forms :as udf]
            [uxbox.util.dom :as dom]))

;; --- Profile Form

(def formdata
  (-> (l/in [:forms :profile/main])
      (l/focus-atom st/state)))

(def formerrors
  (-> (l/in [:errors :profile/main])
      (l/focus-atom st/state)))

(def assign-field-value
  (partial udf/assign-field-value :profile/main))

(def ^:private profile-l
  (-> (l/key :profile)
      (l/focus-atom st/state)))

;; --- Profile Form

(defn profile-form-render
  [own]
  (let [form (merge (rum/react profile-l)
                    (rum/react formdata))
        errors (rum/react formerrors)
        valid? (sc/valid? form udu/update-profile-schema)
        theme (get-in form [:metadata :theme] "light")]

    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (rs/emit! (assign-field-value field value))))

            ;; (on-theme-change [event]
            ;;   (let [value (dom/event->value event)]
            ;;     (println "on-theme-change" value)
            ;;     (swap! local assoc-in [:metadata :theme] value)))


            (on-submit [event]
              ;; (println form)
              (rs/emit! (udu/update-profile form)))]
      (html
       [:form.profile-form
        [:span.user-settings-label "Name, username and email"]
        [:input.input-text
         {:type "text"
          :on-change (partial on-change :fullname)
          :value (:fullname form "")
          :placeholder "Your name"}]
        (forms/input-error errors :fullname)
        [:input.input-text
         {:type "text"
          :on-change (partial on-change :username)
          :value (:username form "")
          :placeholder "Your username"}]
        (forms/input-error errors :username)

        [:input.input-text
         {:type "email"
          :on-change (partial on-change :email)
          :value (:email form "")
          :placeholder "Your email"}]
        (forms/input-error errors :email)

        [:span.user-settings-label "Choose a color theme"]
        [:div.input-radio.radio-primary
         [:input {:type "radio"
                  :checked (= theme "light")
                  :on-change (partial on-change [:metadata :theme])
                  :id "light-theme"
                  :name "theme"
                  :value "light"}]
         [:label {:for "light-theme"} "Light theme"]

         [:input {:type "radio"
                  :checked (= theme "dark")
                  :on-change (partial on-change [:metadata :theme])
                  :id "dark-theme"
                  :name "theme"
                  :value "dark"}]
         [:label {:for "dark-theme"} "Dark theme"]

         [:input {:type "radio"
                  :checked (= theme "high-contrast")
                  :on-change (partial on-change [:metadata :theme])
                  :id "high-contrast-theme"
                  :name "theme"
                  :value "high-contrast"}]
         [:label {:for "high-contrast-theme"} "High-contrast theme"]]

        [:input.btn-primary
         {:type "button"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
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
    (uum/messages)
    [:section.dashboard-content.user-settings
     [:div.user-settings-nav
      [:ul.user-settings-nav-inside
       [:li.current {:on-click #(r/go :settings/profile)} "Profile"]
       [:li {:on-click #(r/go :settings/password)} "Password"]
       [:li {:on-click #(r/go :settings/notifications)} "Notifications"]]]
     [:section.user-settings-content
      [:span.user-settings-label "Your avatar"]
      [:form.avatar-form
       [:img {:src "images/avatar.jpg" :border "0"}]
       [:input {:type "file"}]]
      (profile-form)
      ]]]))

(def profile-page
  (mx/component
   {:render profile-page-render
    :name "profile-page"
    :mixins [mx/static]}))
