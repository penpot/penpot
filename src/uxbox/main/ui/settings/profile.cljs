;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.profile
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.common.schema :as sc]
            [uxbox.common.router :as r]
            [uxbox.main.state :as st]
            [uxbox.common.rstore :as rs]
            [uxbox.main.ui.icons :as i]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.forms :as forms]
            [uxbox.main.ui.settings.header :refer (header)]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.data.users :as udu]
            [uxbox.main.data.forms :as udf]
            [uxbox.util.interop :refer (iterable->seq)]
            [uxbox.util.dom :as dom]))

;; --- Constants

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
            (on-submit [event]
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

;; --- Profile Photo Form

(defn- profile-photo-form-render
  [own]
  (letfn [(on-change [event]
            (let [target (dom/get-target event)
                  file (-> (dom/get-files target)
                           (iterable->seq)
                           (first))]
              (rs/emit! (udu/update-photo file))
              (dom/clean-value! target)))]
    (let [{:keys [photo]} (rum/react profile-l)
          photo (if (or (str/empty? photo) (nil? photo))
                  "images/avatar.jpg"
                  photo)]
    (html
     [:form.avatar-form
      [:img {:src photo :border "0"}]
      [:input {:type "file"
               :value ""
               :on-change on-change}]]))))

(def profile-photo-form
  (mx/component
   {:render profile-photo-form-render
    :name  profile-photo-form
    :mixins [mx/static rum/reactive]}))

;; --- Profile Page

(defn profile-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    (uum/messages)
    [:section.dashboard-content.user-settings
     [:section.user-settings-content
      [:span.user-settings-label "Your avatar"]
      (profile-photo-form)
      (profile-form)
      ]]]))

(def profile-page
  (mx/component
   {:render profile-page-render
    :name "profile-page"
    :mixins [mx/static]}))
