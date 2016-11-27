;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.profile
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.util.forms :as forms]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.interop :refer (iterable->seq)]
            [uxbox.util.dom :as dom]
            [uxbox.store :as st]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.settings.header :refer (header)]
            [uxbox.main.ui.messages :as uum]
            [uxbox.main.data.users :as udu]))

(def form-data (forms/focus-data :profile st/state))
(def form-errors (forms/focus-errors :profile st/state))
(def set-value! (partial forms/set-value! :profile))
(def set-error! (partial forms/set-error! :profile))

(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(def +profile-form+
  {:fullname [forms/required forms/string]
   :email [forms/required forms/email]
   :username [forms/required forms/string]})

;; --- Profile Form

(mx/defc profile-form
  {:mixins [mx/static mx/reactive]
   :will-unmount (forms/cleaner-fn :profile)}
  []
  ;; TODO: properly persist theme
  (let [data (merge {:theme "light"}
                    (mx/react profile-ref)
                    (mx/react form-data))
        errors (mx/react form-errors)
        valid? (forms/valid? data +profile-form+)
        theme (:theme data)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (set-value! field value)))
            (on-error [{:keys [code] :as payload}]
              (case code
                :uxbox.services.users/email-already-exists
                (set-error! :email "Email already exists")
                :uxbox.services.users/username-already-exists
                (set-error! :username "Username already exists")))
            (on-success []
              (forms/clear! :profile))
            (on-submit [event]
              (st/emit! (udu/update-profile data on-success on-error)))]
      [:form.profile-form
       [:span.user-settings-label "Name, username and email"]
       [:input.input-text
        {:type "text"
         :on-change (partial on-change :fullname)
         :value (:fullname data "")
         :placeholder "Your name"}]
       [:input.input-text
        {:type "text"
         :on-change (partial on-change :username)
         :value (:username data "")
         :placeholder "Your username"}]
        (forms/input-error errors :username)

        [:input.input-text
         {:type "email"
          :on-change (partial on-change :email)
          :value (:email data "")
          :placeholder "Your email"}]
        (forms/input-error errors :email)

        [:span.user-settings-label "Choose a color theme"]
        [:div.input-radio.radio-primary
         [:input {:type "radio"
                  :checked (when (= theme "light") "checked")
                  :on-change (partial on-change :theme)
                  :id "light-theme"
                  :name "theme"
                  :value "light"}]
         [:label {:for "light-theme"} "Light theme"]

         [:input {:type "radio"
                  :checked (when (= theme "dark") "checked")
                  :on-change (partial on-change :theme)
                  :id "dark-theme"
                  :name "theme"
                  :value "dark"}]
         [:label {:for "dark-theme"} "Dark theme"]

         [:input {:type "radio"
                  :checked (when (= theme "high-contrast") "checked")
                  :on-change (partial on-change :theme)
                  :id "high-contrast-theme"
                  :name "theme"
                  :value "high-contrast"}]
         [:label {:for "high-contrast-theme"} "High-contrast theme"]]

        [:input.btn-primary
         {:type "button"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :on-click on-submit
          :value "Update settings"}]])))

;; --- Profile Photo Form

(mx/defc profile-photo-form
  {:mixins [mx/static mx/reactive]}
  []
  (letfn [(on-change [event]
            (let [target (dom/get-target event)
                  file (-> (dom/get-files target)
                           (iterable->seq)
                           (first))]
              (st/emit! (udu/update-photo file))
              (dom/clean-value! target)))]
    (let [{:keys [photo]} (mx/react profile-ref)
          photo (if (or (str/empty? photo) (nil? photo))
                  "images/avatar.jpg"
                  photo)]
      [:form.avatar-form
       [:img {:src photo}]
       [:input {:type "file"
                :value ""
                :on-change on-change}]])))

;; --- Profile Page

(mx/defc profile-page
  {:mixins [mx/static]}
  []
  [:main.dashboard-main
   (header)
   (uum/messages)
   [:section.dashboard-content.user-settings
    [:section.user-settings-content
     [:span.user-settings-label "Your avatar"]
     (profile-photo-form)
     (profile-form)]]])
