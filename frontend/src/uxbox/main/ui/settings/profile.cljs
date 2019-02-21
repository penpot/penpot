;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.profile
  (:require [cljs.spec :as s :include-macros true]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.settings.header :refer [header]]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.data.users :as udu]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.forms :as fm]
            [uxbox.util.router :as r]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.interop :refer [iterable->seq]]
            [uxbox.util.dom :as dom]))


(def form-data (fm/focus-data :profile st/state))
(def form-errors (fm/focus-errors :profile st/state))

(def assoc-value (partial fm/assoc-value :profile))
(def assoc-error (partial fm/assoc-error :profile))
(def clear-form (partial fm/clear-form :profile))

(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(s/def ::fullname ::fm/non-empty-string)
(s/def ::username ::fm/non-empty-string)
(s/def ::email ::fm/email)

(s/def ::profile-form
  (s/keys :req-un [::fullname
                   ::username
                   ::email]))

;; --- Profile Form

(mx/defc profile-form
  {:mixins [mx/static mx/reactive
            (fm/clear-mixin st/store :profile)]}
  []
  (let [data (merge {:theme "light"}
                    (mx/react profile-ref)
                    (mx/react form-data))
        errors (mx/react form-errors)
        valid? (fm/valid? ::profile-form data)
        theme (:theme data)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (st/emit! (assoc-value field value))))
            (on-error [{:keys [code] :as payload}]
              (case code
                :uxbox.services.users/registration-disabled
                (st/emit! (tr "errors.api.form.registration-disabled"))
                :uxbox.services.users/email-already-exists
                (st/emit! (assoc-error :email (tr "errors.api.form.email-already-exists")))
                :uxbox.services.users/username-already-exists
                (st/emit! (assoc-error :username (tr "errors.api.form.username-already-exists")))))
            (on-success [_]
              (st/emit! (clear-form)))
            (on-submit [event]
              (st/emit! (udu/update-profile data on-success on-error)))]
      [:form.profile-form
       [:span.user-settings-label (tr "settings.profile.profile.profile-saved")]
       [:input.input-text
        {:type "text"
         :on-change (partial on-change :fullname)
         :value (:fullname data "")
         :placeholder (tr "settings.profile.your-name")}]
       [:input.input-text
        {:type "text"
         :on-change (partial on-change :username)
         :value (:username data "")
         :placeholder (tr "settings.profile.your-username")}]
       (fm/input-error errors :username)

       [:input.input-text
        {:type "email"
         :on-change (partial on-change :email)
         :value (:email data "")
         :placeholder (tr "settings.profile.your-email")}]
       (fm/input-error errors :email)

        #_[:span.user-settings-label (tr "settings.choose-color-theme")]
        #_[:div.input-radio.radio-primary
         [:input {:type "radio"
                  :checked (when (= theme "light") "checked")
                  :on-change (partial on-change :theme)
                  :id "light-theme"
                  :name "theme"
                  :value "light"}]
         [:label {:for "light-theme"} (tr "settings.profile.light-theme")]

         [:input {:type "radio"
                  :checked (when (= theme "dark") "checked")
                  :on-change (partial on-change :theme)
                  :id "dark-theme"
                  :name "theme"
                  :value "dark"}]
         [:label {:for "dark-theme"} (tr "settings.profile.dark-theme")]

         [:input {:type "radio"
                  :checked (when (= theme "high-contrast") "checked")
                  :on-change (partial on-change :theme)
                  :id "high-contrast-theme"
                  :name "theme"
                  :value "high-contrast"}]
         [:label {:for "high-contrast-theme"} (tr "settings.profile.high-contrast-theme")]]

        [:input.btn-primary
         {:type "button"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :on-click on-submit
          :value (tr "settings.update-settings")}]])))

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
   (messages-widget)
   (header)
   [:section.dashboard-content.user-settings
    [:section.user-settings-content
     [:span.user-settings-label (tr "settings.profile.your-avatar")]
     (profile-photo-form)
     (profile-form)]]])
