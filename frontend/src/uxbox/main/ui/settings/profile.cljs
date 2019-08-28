;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.profile
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [struct.core :as s]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.interop :refer [iterable->seq]]))

(defn profile->form
  [profile]
  (let [language (get-in profile [:metadata :language])]
    (-> (select-keys profile [:fullname :username :email])
        (cond-> language (assoc :language language)))))

(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(s/defs profile-form-spec
  {:fullname [fm/required fm/string]
   :username [fm/required fm/string]
   :email    [fm/required fm/email]
   :language [fm/required fm/string]})

(defn- on-error
  [error form]
  (prn "on-error" error form)
  (case (:code error)
    :uxbox.services.users/email-already-exists
    (swap! form assoc-in [:errors :email]
           {:type ::api
            :message "errors.api.form.email-already-exists"})

    :uxbox.services.users/username-already-exists
    (swap! form assoc-in [:errors :username]
           {:type ::api
            :message "errors.api.form.username-already-exists"})))

(defn- initial-data
  []
  (merge {:language @i18n/locale}
         (profile->form (deref profile-ref))))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [data (:clean-data form)
        opts {:on-success #(prn "On Success" %)
              :on-error #(on-error % form)}]
    (st/emit! (udu/update-profile data opts))))

;; --- Profile Form
(mf/defc profile-form
  [props]
  (let [{:keys [data] :as form} (fm/use-form {:initial initial-data
                                              :spec profile-form-spec})]
    (prn "profile-form" form)
    [:form.profile-form {:on-submit #(on-submit % form)}
     [:span.user-settings-label (tr "settings.profile.section-basic-data")]
     [:input.input-text
      {:type "text"
       :name "fullname"
       :class (fm/error-class form :fullname)
       :on-blur (fm/on-input-blur form :fullname)
       :on-change (fm/on-input-change form :fullname)
       :value (:fullname data "")
       :placeholder (tr "settings.profile.your-name")}]

     [:& fm/field-error {:form form
                         :type #{::api}
                         :field :fullname}]
     [:input.input-text
      {:type "text"
       :name "username"
       :class (fm/error-class form :username)
       :on-blur (fm/on-input-blur form :username)
       :on-change (fm/on-input-change form :username)
       :value (:username data "")
       :placeholder (tr "settings.profile.your-username")}]

     [:& fm/field-error {:form form
                         :type #{::api}
                         :field :username}]

     [:input.input-text
      {:type "email"
       :name "email"
       :class (fm/error-class form :email)
       :on-blur (fm/on-input-blur form :email)
       :on-change (fm/on-input-change form :email)
       :value (:email data "")
       :placeholder (tr "settings.profile.your-email")}]

     [:& fm/field-error {:form form
                         :type #{::api}
                         :field :email}]

     [:span.user-settings-label (tr "settings.profile.section-i18n-data")]
     [:select.input-select {:value (:language data)
                            :name "language"
                            :class (fm/error-class form :language)
                            :on-blur (fm/on-input-blur form :language)
                            :on-change (fm/on-input-change form :language)}
      [:option {:value "en"} "English"]
      [:option {:value "fr"} "Français"]]

     [:input.btn-primary
      {:type "submit"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))
       :value (tr "settings.update-settings")}]]))

;; --- Profile Photo Form

(mf/defc profile-photo-form
  []
  (letfn [(on-change [event]
            (let [target (dom/get-target event)
                  file (-> (dom/get-files target)
                           (iterable->seq)
                           (first))]
              (st/emit! (udu/update-photo file))
              (dom/clean-value! target)))]
    (let [{:keys [photo] :as profile} (mf/deref profile-ref)
          photo (if (or (str/empty? photo) (nil? photo))
                  "images/avatar.jpg"
                  photo)]
      [:form.avatar-form
       [:img {:src photo}]
       [:input {:type "file"
                :value ""
                :on-change on-change}]])))

;; --- Profile Page

(mf/defc profile-page
  []
  [:section.dashboard-content.user-settings
   [:section.user-settings-content
    [:span.user-settings-label (tr "settings.profile.your-avatar")]
    [:& profile-photo-form]
    [:& profile-form]]])
